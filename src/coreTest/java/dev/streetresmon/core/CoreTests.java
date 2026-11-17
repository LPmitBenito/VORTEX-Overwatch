package dev.streetresmon.core;

import dev.streetresmon.core.crash.CrashAnalyzer;
import dev.streetresmon.core.input.InputTracker;
import dev.streetresmon.core.logs.*;
import dev.streetresmon.core.memory.MemoryTrendDetector;
import dev.streetresmon.core.memory.MemoryTrendDetector.MemSample;
import dev.streetresmon.core.module.ModuleRegistry;
import dev.streetresmon.core.module.Side;
import dev.streetresmon.core.module.StreetModule;
import dev.streetresmon.core.net.NetStats;
import dev.streetresmon.core.owner.ModOwnerResolver;
import dev.streetresmon.core.pool.PoolRegistry;
import dev.streetresmon.core.profiler.*;
import dev.streetresmon.core.resmon.EventTimingTable;
import dev.streetresmon.core.resmon.ModSampleTable;
import dev.streetresmon.core.resmon.StackSampler;
import dev.streetresmon.core.settings.CoreSettings;
import dev.streetresmon.core.startup.StartupTracker;
import dev.streetresmon.core.util.RingBuffer;
import dev.streetresmon.core.util.Stats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free test runner (no JUnit available offline). Exit code 1 if any test fails. */
public final class CoreTests {
    private static int passed, failed;

    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    private static void eq(Object exp, Object act, String msg) {
        if (!java.util.Objects.equals(exp, act)) throw new AssertionError(msg + " expected=" + exp + " actual=" + act);
    }

    private static void near(double exp, double act, double eps, String msg) {
        if (Math.abs(exp - act) > eps) throw new AssertionError(msg + " expected=" + exp + " actual=" + act);
    }

    private interface T { void run() throws Exception; }

    private static void test(String name, T t) {
        try {
            t.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + " -> " + e);
        }
    }

    private static StackTraceElement f(String cls, String m) { return new StackTraceElement(cls, m, "X.java", 1); }

    public static void main(String[] args) throws Exception {
        test("Metric formatting and quality", () -> {
            eq("n/a", Metric.unavailable("x").format(), "unavailable");
            eq("~1.50 ms", Metric.estimated(1.5, "ms", "s").format(), "estimated");
            eq("2.00 ms", Metric.measured(2, "ms").format(), "measured");
            check(!Metric.measured(Double.NaN, "").available(), "NaN is not available");
        });
        test("RingBuffer wraps and keeps order", () -> {
            RingBuffer r = new RingBuffer(3);
            for (int i = 1; i <= 5; i++) r.add(i);
            check(java.util.Arrays.equals(new double[] {3, 4, 5}, r.toArray()), "order");
            eq(5.0, r.last(), "last");
            r.clear();
            eq(0, r.size(), "cleared");
        });
        test("Stats ignore NaN, 1% low", () -> {
            near(2, Stats.avg(new double[] {1, Double.NaN, 3}), 1e-9, "avg");
            check(Double.isNaN(Stats.avg(new double[] {Double.NaN})), "all NaN");
            double[] v = new double[100];
            for (int i = 0; i < 100; i++) v[i] = i < 1 ? 10 : 60;
            near(10, Stats.lowFractionAvg(v, 0.01), 1e-9, "1% low");
            near(2.5, Stats.median(new double[] {1, 2, 3, 4}), 1e-9, "median");
        });
        test("ModuleRegistry: duplicates, side filter, failure isolation", () -> {
            ModuleRegistry reg = new ModuleRegistry();
            List<String> log = new ArrayList<>();
            reg.register(mod("a", Side.BOTH, log, false));
            reg.register(mod("bad", Side.BOTH, log, true));
            reg.register(mod("c", Side.CLIENT, log, false));
            reg.register(mod("s", Side.SERVER, log, false));
            boolean dup = false;
            try { reg.register(mod("a", Side.BOTH, log, false)); } catch (IllegalArgumentException e) { dup = true; }
            check(dup, "duplicate rejected");
            Map<String, Throwable> fails = reg.startAll(Side.SERVER, Set.of("a", "bad", "c", "s"));
            eq(Set.of("bad"), fails.keySet(), "only bad failed");
            check(reg.isStarted("a") && reg.isStarted("s") && !reg.isStarted("c"), "side filter");
            reg.stopAll();
            check(log.contains("stop:s") && log.indexOf("stop:s") < log.indexOf("stop:a"), "reverse stop order");
        });
        test("CoreSettings roundtrip, defaults, invalid values", () -> {
            Path dir = Files.createTempDirectory("srm");
            Path file = dir.resolve("sub/settings.properties");
            CoreSettings s = new CoreSettings();
            s.set("overlay.x", 42); s.set("hotkey.resmon", "F5"); s.set("bad", "abc"); s.set("flag", "true");
            s.save(file);
            CoreSettings l = new CoreSettings();
            l.load(file);
            eq(42, l.getInt("overlay.x", 0), "int");
            eq("F5", l.getString("hotkey.resmon", ""), "string");
            eq(7, l.getInt("bad", 7), "invalid -> default");
            eq(3, l.getIntClamped("overlay.x", 3, 0, 3), "clamp");
            check(l.getBoolean("flag", false) && !l.getBoolean("missing", false), "bool");
            new CoreSettings().load(dir.resolve("nope")); // missing file ok
        });
        test("ModOwnerResolver longest prefix", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.register("examplemod", "com.example.examplemod");
            eq("examplemod", r.resolve("com.example.examplemod.sub.Foo"), "mod");
            eq(ModOwnerResolver.VANILLA, r.resolve("net.minecraft.client.Minecraft"), "vanilla");
            eq(ModOwnerResolver.LIBRARY, r.resolve("com.mojang.blaze3d.systems.RenderSystem"), "blaze3d override");
            eq(ModOwnerResolver.VANILLA, r.resolve("com.mojang.serialization.Codec"), "mojang");
            eq(null, r.resolve("org.unknown.Thing"), "unknown stays null");
            check(ModOwnerResolver.isMod("examplemod") && !ModOwnerResolver.isMod("minecraft"), "isMod");
        });
        test("ModSampleTable self vs inclusive, idle, JVM frames", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.register("modA", "com.a");
            r.register("modB", "com.b");
            ModSampleTable t = new ModSampleTable(r, 10, 0);
            // modA calls vanilla which calls JDK: self=minecraft, inclusive={minecraft,modA}
            t.record(new StackTraceElement[] {f("java.util.HashMap", "get"), f("net.minecraft.world.Level", "tick"), f("com.a.Foo", "run")});
            // modB calls JDK: self=modB
            t.record(new StackTraceElement[] {f("java.util.HashMap", "get"), f("com.b.Bar", "run")});
            t.record(new StackTraceElement[] {f("com.b.Bar", "run")});
            t.record(new StackTraceElement[] {new StackTraceElement("x.Y", "park", "Y.java", -2)}); // native -> idle
            t.record(new StackTraceElement[0]);
            eq(3L, t.busySamples(), "busy");
            eq(2L, t.idleSamples(), "idle");
            Map<String, ModSampleTable.Row> m = new java.util.HashMap<>();
            for (ModSampleTable.Row row : t.snapshot(1_000_000_000L)) m.put(row.ownerId(), row);
            eq(2L, m.get("modB").selfSamples(), "modB self");
            eq(0L, m.get("modA").selfSamples(), "modA self");
            eq(1L, m.get("modA").inclusiveSamples(), "modA incl");
            eq(1L, m.get("minecraft").selfSamples(), "minecraft self");
            near(100.0 * 2 / 3, m.get("modB").selfPercent(), 1e-6, "percent");
            near(2 * 10 * 1000.0 / 1000.0, m.get("modB").estSelfMsPerSec(), 1e-6, "ms/s over 1000 ms");
            eq("modB", t.snapshot(1_000_000_000L).get(0).ownerId(), "sorted by self");
            t.reset(5);
            eq(0L, t.busySamples(), "reset");
        });
        test("ModOwnerResolver id heuristic: matches package segment, never overrides, no false positives", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.registerModIds(List.of("examplemod", "my_cool-mod", "ab", "minecraft", "neoforge", "create"));
            eq("examplemod", r.resolve("com.example.examplemod.sub.Foo"), "segment equals id");
            eq("my_cool-mod", r.resolve("org.me.mycoolmod.Bar"), "normalized id");
            eq("create", r.resolve("com.simibubi.create.content.X"), "create");
            eq(null, r.resolve("org.ab.Thing"), "ids shorter than 3 ignored");
            eq(null, r.resolve("org.other.Thing"), "unknown stays null");
            eq(null, r.resolve("com.foo.examplemod"), "class-name segment itself never matches");
            eq(ModOwnerResolver.VANILLA, r.resolve("net.minecraft.examplemod.Fake"), "registered prefix wins over heuristic");
            eq(null, r.resolve("com.foo.minecraft.Thing"), "minecraft id ignored by heuristic");
            r.register("manual", "org.me.mycoolmod.special");
            eq("manual", r.resolve("org.me.mycoolmod.special.Z"), "longer manual prefix wins");
        });
        test("ModSampleTable.recentSummary", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.register("modA", "com.a");
            ModSampleTable t = new ModSampleTable(r, 10, 0);
            check(t.recentSummary(16, 3).isEmpty(), "empty");
            for (int i = 0; i < 6; i++) t.record(new StackTraceElement[] {f("com.a.X", "m")});
            for (int i = 0; i < 2; i++) t.record(new StackTraceElement[] {f("net.minecraft.Y", "m")});
            eq("minecraft 2/2", t.recentSummary(2, 3), "last two");
            eq("modA 6/8, minecraft 2/8", t.recentSummary(16, 3), "all");
            t.reset(0);
            check(t.recentSummary(16, 3).isEmpty(), "reset clears");
        });
        test("StackSampler attributes a busy thread to its registered package (live)", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.register("testmod", "dev.streetresmon.core");
            ModSampleTable table = new ModSampleTable(r, 5, System.nanoTime());
            final boolean[] stop = {false};
            Thread worker = new Thread(() -> { double x = 0; while (!stop[0]) x += Math.sin(x + 1); if (x == 42) System.out.print(""); }, "busy");
            worker.setDaemon(true);
            worker.start();
            try (StackSampler s = new StackSampler(worker, table, 5)) {
                s.start();
                Thread.sleep(600);
            }
            stop[0] = true;
            List<ModSampleTable.Row> rows = table.snapshot(System.nanoTime());
            check(table.busySamples() > 20, "enough samples: " + table.busySamples());
            eq("testmod", rows.get(0).ownerId(), "busy thread charged to testmod");
        });
        test("EventTimingTable aggregates and caps keys", () -> {
            EventTimingTable t = new EventTimingTable(2);
            t.record("TickEvent", "a", 2_000_000);
            t.record("TickEvent", "a", 4_000_000);
            t.record("X", "b", 1_000_000);
            t.record("Y", "c", 1_000_000); // over cap -> (other)
            eq(3, t.distinctKeys(), "2 keys + overflow");
            EventTimingTable.Row top = t.byTotalTime().get(0);
            eq("TickEvent", top.event(), "top");
            eq(2L, top.count(), "count");
            near(6.0, top.totalMs(), 1e-9, "total");
            near(3.0, top.avgMs(), 1e-9, "avg");
            near(4.0, top.maxMs(), 1e-9, "max");
        });
        test("SpikeDetector finds a stall once, respects cooldown, lazy hint", () -> {
            SpikeDetector d = SpikeDetector.defaults();
            int[] hintCalls = {0};
            List<Spike> all = new ArrayList<>();
            for (int i = 0; i < 60; i++) all.addAll(d.onSample(sample(i * 16L, 16.6, 5, Double.NaN), () -> { hintCalls[0]++; return "h"; }));
            eq(0, all.size(), "no spike in steady state");
            eq(0, hintCalls[0], "hint not evaluated without spike");
            all.addAll(d.onSample(sample(1000, 250, 5, Double.NaN), () -> { hintCalls[0]++; return "modA ~"; }));
            all.addAll(d.onSample(sample(1100, 260, 5, Double.NaN), () -> "x")); // within cooldown
            eq(1, all.size(), "exactly one spike");
            eq(SpikeDetector.FRAME, all.get(0).kind(), "kind");
            eq("modA ~", all.get(0).hint(), "hint");
            eq(1, hintCalls[0], "hint evaluated once");
        });
        test("SpikeDetector: server tick, NaN ignored", () -> {
            SpikeDetector d = SpikeDetector.defaults();
            for (int i = 0; i < 60; i++) d.onSample(sample(i * 50L, 16, 5, 20), null);
            List<Spike> s = d.onSample(sample(5000, 16, 5, 400), null);
            eq(1, s.size(), "one");
            eq(SpikeDetector.SERVER_TICK, s.get(0).kind(), "server");
        });
        test("ProfilerSession cap + Recording summary", () -> {
            ProfilerSession ps = new ProfilerSession(100, SpikeDetector.defaults());
            check(ps.start("t", 0) && !ps.start("again", 0), "single start");
            for (int i = 0; i < 150; i++) ps.add(sample(i * 20L, 1000.0 / 60, 5, Double.NaN), null);
            eq(100, ps.sampleCount(), "capped");
            Recording r = ps.stop(3000);
            check(r.truncated, "truncated flag");
            eq(null, ps.stop(1), "stop when idle");
            Recording.Summary s = r.summary();
            near(60, s.avgFps(), 1e-9, "avg fps");
            near(60, s.low1PercentFps(), 1e-9, "1% low");
            check(Double.isNaN(s.avgMsptMs()), "mspt unavailable stays NaN");
            near(3.0, s.durationSec(), 1e-9, "duration");
        });
        test("RecordingIO roundtrip incl. NaN, tabs and newlines in hints", () -> {
            Path dir = Files.createTempDirectory("srm");
            List<FrameSample> s = List.of(sample(1, 60, 5, Double.NaN), sample(2, 30, 40, 55));
            List<Spike> p = List.of(new Spike(2, "FRAME", 99.5, 16.6, "a\tb\nc\\d"));
            Recording r = new Recording("na me", 10, 20, true, s, p);
            Path f = dir.resolve(RecordingIO.sanitizeFileName("na me") + ".srec");
            RecordingIO.save(r, f);
            check(!Files.exists(f.resolveSibling(f.getFileName() + ".tmp")), "tmp removed");
            Recording l = RecordingIO.load(f);
            eq("na me", l.name, "name");
            eq(2, l.samples.size(), "samples");
            check(Double.isNaN(l.samples.get(0).serverMspt()), "NaN preserved");
            eq("a\tb\nc\\d", l.spikes.get(0).hint(), "hint escaped");
            check(l.truncated, "flag");
            Files.writeString(dir.resolve("bad.srec"), "garbage\n");
            boolean threw = false;
            try { RecordingIO.load(dir.resolve("bad.srec")); } catch (java.io.IOException e) { threw = true; }
            check(threw, "bad header rejected");
            Files.writeString(dir.resolve("corrupt.srec"), "STREETRESMON-REC 1\nS\t1\tabc\n");
            threw = false;
            try { RecordingIO.load(dir.resolve("corrupt.srec")); } catch (java.io.IOException e) { threw = true; }
            check(threw, "corrupt line -> IOException, not RuntimeException");
        });
        test("RecordingComparer", () -> {
            Recording a = new Recording("A", 0, 1000, false, List.of(sample(1, 1000.0 / 60, 5, Double.NaN)), List.of());
            Recording b = new Recording("B", 0, 1000, false, List.of(sample(1, 1000.0 / 90, 5, Double.NaN)), List.of());
            RecordingComparer.Row fps = RecordingComparer.compare(a, b).get(0);
            near(30, fps.delta(), 1e-9, "delta");
            near(50, fps.deltaPercent(), 1e-9, "percent");
            eq(Boolean.TRUE, fps.bIsBetter(), "B better");
            check(RecordingComparer.toText(a, b).contains("Avg FPS"), "text");
        });
        test("NetStats rates, stale ping, disconnects, no invented traffic", () -> {
            NetStats n = new NetStats(60, 15_000);
            NetStats.Snapshot first = n.sample(0);
            check(!first.trafficMeasured() && !first.pingMs().available(), "nothing measured yet");
            for (int i = 0; i < 10; i++) n.recordIn(100);
            n.recordOut(50);
            n.recordPing(76, 1000);
            NetStats.Snapshot s = n.sample(2000);
            near(5, s.packetsInPerSec(), 1e-9, "in pkts/s");
            near(500, s.bytesInPerSec(), 1e-9, "in B/s");
            near(25, s.bytesOutPerSec(), 1e-9, "out B/s");
            check(s.trafficMeasured(), "traffic measured");
            eq(76.0, s.pingMs().value(), "ping");
            check(!n.sample(30_000).pingMs().available(), "stale ping hidden");
            n.setConnected(true); n.setConnected(false); n.setConnected(false);
            eq(1, n.disconnects(), "one disconnect");
            n.recordError("boom");
            eq(1, n.errors(), "errors");
        });
        test("MemoryTrendDetector: rising / stable / insufficient / jump", () -> {
            MemoryTrendDetector rising = MemoryTrendDetector.defaults();
            long gc = 0;
            for (int s = 0; s <= 900; s++) { // 15 min, GC every 30 s, floor +5 MB/min
                long t = s * 1000L;
                if (s % 30 == 0) gc++;
                rising.add(new MemSample(t, mb(500 + 5.0 * s / 60.0 + (s % 30)), mb(4096), gc, gc * 10));
            }
            eq(MemoryTrendDetector.Verdict.RISING_UNCONFIRMED, rising.report().verdict(), "rising");
            near(5.0, rising.report().slopeMbPerMin(), 0.2, "slope");
            check(rising.report().explanation().contains("not prove"), "never claims certainty");

            MemoryTrendDetector flat = MemoryTrendDetector.defaults();
            gc = 0;
            for (int s = 0; s <= 900; s++) {
                if (s % 30 == 0) gc++;
                flat.add(new MemSample(s * 1000L, mb(500 + (s % 30) * 3), mb(4096), gc, gc));
            }
            eq(MemoryTrendDetector.Verdict.STABLE, flat.report().verdict(), "stable");

            MemoryTrendDetector few = MemoryTrendDetector.defaults();
            few.add(new MemSample(0, mb(100), mb(1000), 0, 0));
            few.add(new MemSample(1000, mb(120), mb(1000), 1, 1));
            eq(MemoryTrendDetector.Verdict.INSUFFICIENT_DATA, few.report().verdict(), "few");

            MemoryTrendDetector j = MemoryTrendDetector.defaults();
            j.add(new MemSample(0, mb(100), mb(2000), 0, 0));
            j.add(new MemSample(1000, mb(500), mb(2000), 0, 0)); // +400 MB, no GC
            eq(1, j.jumps().size(), "jump detected");
            near(400, j.jumps().get(0).deltaMb(), 1e-6, "jump size");
        });
        test("StartupTracker with fake clock", () -> {
            AtomicLong clock = new AtomicLong();
            StartupTracker st = new StartupTracker(clock::get);
            st.begin("modA init"); clock.addAndGet(5_000_000);
            check(st.end("modA init", "modA"), "end ok");
            check(!st.end("never begun", "x"), "unknown end rejected");
            st.begin("modB init"); clock.addAndGet(250_000_000); st.end("modB init", "modB");
            st.record("registry", "minecraft", 20_000_000);
            eq("modB init", st.sortedByDuration().get(0).name(), "slowest first");
            eq(1, st.slowPhases(100).size(), "slow filter");
            near(275, st.totalTrackedMs(), 1e-6, "total");
            st.problem("ERROR", "x"); st.problem("WARN", "y");
            eq(1L, st.countProblems("error"), "problem count");
            check(StartupTracker.jvmUptime().available(), "uptime measured");
        });
        final String trace = String.join("\n",
                "---- Minecraft Crash Report ----",
                "Description: Ticking entity",
                "",
                "java.lang.RuntimeException: Ticking entity",
                "\tat net.minecraft.server.level.ServerLevel.tick(ServerLevel.java:300) ~[client.jar:?]",
                "Caused by: java.lang.NullPointerException: Cannot invoke \"Object.toString()\"",
                "\tat com.example.examplemod.ExampleClass.doThing(ExampleClass.java:125) ~[examplemod-1.0.jar%23123!/:1.0]",
                "\tat net.minecraft.world.entity.Entity.tick(Entity.java:10)",
                "\tat java.base/java.lang.Thread.run(Thread.java:1583)");
        test("CrashAnalyzer: cause, location, category, honest confidence", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.register("examplemod", "com.example.examplemod");
            CrashAnalyzer.Report rep = new CrashAnalyzer(r).analyze(trace);
            check(rep.detected() && rep.fullCrashReport(), "detected");
            eq("java.lang.NullPointerException", rep.exceptionType(), "root cause type");
            eq(CrashAnalyzer.Category.NULL_POINTER, rep.category(), "category");
            eq("examplemod", rep.suspect(), "suspect");
            eq("ExampleClass.java:125", rep.location(), "location");
            eq(CrashAnalyzer.Confidence.UNCONFIRMED, rep.confidence(), "never 'confirmed'");
            eq("Ticking entity", rep.description(), "description");
            String text = rep.toText();
            check(text.contains("STREET RESMON CRASH ANALYZER") && text.contains("Crash detected!") && text.contains("Unconfirmed"), "text format");
        });
        test("CrashAnalyzer: unregistered packages are NOT guessed; no trace; module hint ignored as owner", () -> {
            CrashAnalyzer a = new CrashAnalyzer(new ModOwnerResolver());
            CrashAnalyzer.Report rep = a.analyze(trace);
            eq(null, rep.suspect(), "no suspect without registration");
            eq(CrashAnalyzer.Confidence.NONE, rep.confidence(), "no confidence");
            check(!a.analyze("just some text\nnothing here").detected(), "no trace -> not detected");
            check(a.analyze("just some text").toText().contains("No crash"), "text");
        });
        test("CrashAnalyzer: weak confidence when mod is deep; categories; grouping", () -> {
            ModOwnerResolver r = new ModOwnerResolver();
            r.register("deepmod", "org.deep");
            String t = "java.lang.OutOfMemoryError: Java heap space\n"
                    + "\tat net.minecraft.A.a(A.java:1)\n\tat net.minecraft.A.b(A.java:2)\n\tat net.minecraft.A.c(A.java:3)\n"
                    + "\tat org.deep.Foo.bar(Foo.java:9)\n";
            CrashAnalyzer a = new CrashAnalyzer(r);
            CrashAnalyzer.Report rep = a.analyze(t);
            eq(CrashAnalyzer.Category.OUT_OF_MEMORY, rep.category(), "oom");
            eq(CrashAnalyzer.Confidence.UNCONFIRMED_WEAK, rep.confidence(), "weak");
            String npe = "java.lang.NullPointerException: x\n\tat org.deep.Foo.bar(Foo.java:9)\n";
            List<CrashAnalyzer.ErrorGroup> g = a.groupErrors(npe + "\n" + npe + "\n" + t);
            eq(2, g.size(), "two groups");
            eq(2, g.get(0).count(), "NPE counted twice");
            eq("deepmod", g.get(0).suspect(), "group suspect");
            eq(CrashAnalyzer.Category.MIXIN, a.analyze("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: Mixin apply failed\n\tat a.B.c(B.java:1)").category(), "mixin");
        });
        test("LogAssembler / LogBuffer: parse, continuation lines, filter, cap, export", () -> {
            LogAssembler as = new LogAssembler();
            LogBuffer buf = new LogBuffer(3);
            String[] lines = {
                    "[17Nov2026 10:06:01.123] [Render thread/INFO] [net.minecraft.client.Minecraft/]: Hello",
                    "[10:06:02] [main/WARN] [examplemod/Foo]: careful",
                    "[10:06:03] [main/ERROR] [examplemod/Foo]: broke",
                    "java.lang.NullPointerException: x",
                    "\tat com.example.Foo.bar(Foo.java:1)",
                    "[10:06:04] [Server thread/DEBUG]: dbg",
            };
            for (String l : lines) { LogEntry e = as.feed(l); if (e != null) buf.add(e); }
            eq(3, buf.size(), "capped at 3");
            eq(1L, buf.dropped(), "one dropped");
            List<LogEntry> snap = buf.snapshot();
            eq(LogLevel.WARNING, snap.get(0).level, "WARN -> WARNING");
            eq(2, snap.get(1).details().size(), "stack trace attached to ERROR");
            eq(LogLevel.DEBUG, snap.get(2).level, "debug");
            eq("", snap.get(2).source, "optional source");
            eq(1, buf.filter(LogLevel.ERROR, "").size(), "level filter");
            eq(1, buf.filter(LogLevel.DEBUG, "NULLPOINTER").size(), "search inside details, case-insensitive");
            eq(2, buf.lastProblems(5).size(), "problems");
            check(LogBuffer.exportText(snap).contains("NullPointerException"), "export");
        });
        test("LogTailer: no duplicates, partial line, truncation, CRLF, UTF-8, initial skip", () -> {
            Path dir = Files.createTempDirectory("srm");
            Path f = dir.resolve("latest.log");
            LogTailer t = new LogTailer(f, 1 << 16, 1 << 20);
            check(t.poll().isEmpty(), "missing file");
            Files.writeString(f, "one\r\ntwo\nthr");
            eq(List.of("one", "two"), t.poll(), "complete lines only");
            check(t.poll().isEmpty(), "no duplicate on 2nd poll");
            Files.writeString(f, "ee ä\nfour\n", StandardOpenOption.APPEND);
            eq(List.of("three ä", "four"), t.poll(), "partial completed, UTF-8 intact");
            Files.writeString(f, "new\n"); // truncate/rotate
            eq(List.of("new"), t.poll(), "after truncation");

            Path big = dir.resolve("big.log");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) sb.append("line").append(i).append('\n');
            Files.writeString(big, sb.toString());
            List<String> got = new LogTailer(big, 1 << 20, 500).poll();
            check(!got.isEmpty() && got.get(got.size() - 1).equals("line999"), "tail end read");
            check(got.get(0).startsWith("line"), "first partial line dropped: " + got.get(0));
            check(got.size() < 1000, "initial window limited");
        });
        test("PoolRegistry: unavailable instead of invented values, side filter", () -> {
            PoolRegistry p = new PoolRegistry();
            p.register(new PoolRegistry.Gauge("e", "Entities", PoolRegistry.Category.ENTITY, Side.BOTH, () -> 12L, "test"));
            p.register(new PoolRegistry.Gauge("n", "Unknown", PoolRegistry.Category.OTHER, Side.BOTH, () -> null, "test"));
            p.register(new PoolRegistry.Gauge("x", "Throws", PoolRegistry.Category.OTHER, Side.BOTH, () -> { throw new IllegalStateException(); }, "test"));
            p.register(new PoolRegistry.Gauge("p", "Particles", PoolRegistry.Category.PARTICLE, Side.CLIENT, () -> 5L, "test"));
            List<PoolRegistry.Reading> r = p.readAll(Side.SERVER);
            eq(3, r.size(), "client gauge hidden on server");
            eq(12.0, r.get(0).metric().value(), "value");
            check(!r.get(1).metric().available() && !r.get(2).metric().available(), "unavailable");
            boolean dup = false;
            try { p.register(new PoolRegistry.Gauge("e", "", PoolRegistry.Category.OTHER, Side.BOTH, () -> 1L, "")); } catch (IllegalArgumentException e) { dup = true; }
            check(dup, "dup rejected");
        });
        test("InputTracker: combos, buttons, scroll decay, CPS, speed, releaseAll", () -> {
            InputTracker in = new InputTracker();
            in.keyDown(30, "A"); in.keyDown(29, "Ctrl"); in.keyDown(30, "A");
            in.mouse(0, true, 100); in.mouse(0, false, 120); in.mouse(0, true, 200);
            in.mouse(1, true, 300);
            in.scroll(-1, 400);
            in.mouseMove(3, 4, 450);
            InputTracker.Snapshot s = in.snapshot(500);
            eq("Ctrl+A", s.combo(), "modifier first, no duplicate");
            check(s.left() && s.right() && !s.middle(), "buttons");
            eq(2, s.leftCps(), "left cps");
            eq(1, s.rightCps(), "right cps");
            eq(-1, s.scrollDirection(), "scroll visible");
            near(5.0, s.mouseSpeedPxPerSec(), 1e-6, "speed");
            InputTracker.Snapshot later = in.snapshot(2000);
            eq(0, later.scrollDirection(), "scroll decayed");
            eq(0, later.leftCps(), "cps window expired");
            in.releaseAll();
            check(in.snapshot(2001).keys().isEmpty() && !in.snapshot(2001).left(), "releaseAll");
        });

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static long mb(double v) { return (long) (v * 1024 * 1024); }

    private static FrameSample sample(long t, double frameMs, double clientTick, double mspt) {
        return new FrameSample(t, 1000.0 / frameMs, frameMs, clientTick, mspt, 100, Double.NaN, 0, 0);
    }

    private static StreetModule mod(String id, Side side, List<String> log, boolean failStart) {
        return new StreetModule() {
            public String id() { return id; }
            public String displayName() { return id; }
            public Side side() { return side; }
            public void start() { if (failStart) throw new IllegalStateException("fail"); }
            public void stop() { log.add("stop:" + id); }
        };
    }
}
