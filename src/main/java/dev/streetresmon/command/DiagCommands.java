package dev.streetresmon.command;

import dev.streetresmon.StreetPaths;
import dev.streetresmon.core.crash.CrashAnalyzer;
import dev.streetresmon.core.logs.LogTailer;
import dev.streetresmon.core.memory.MemoryTrendDetector;
import dev.streetresmon.core.profiler.Recording;
import dev.streetresmon.core.profiler.RecordingComparer;
import dev.streetresmon.core.profiler.RecordingIO;
import dev.streetresmon.core.profiler.Spike;
import dev.streetresmon.perf.Background;
import dev.streetresmon.perf.MemoryHub;
import dev.streetresmon.perf.ProfilerHub;
import dev.streetresmon.perf.SamplerHub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Diagnostic commands: profiler, memory, crashlog. File access and analysis run on the background thread. */
final class DiagCommands {
    private DiagCommands() {}

    private static Path resolveRecording(String name) {
        String n = RecordingIO.sanitizeFileName(name);
        return StreetPaths.profilerDir().resolve(n.endsWith(".srec") ? n : n + ".srec");
    }

    private static void printSummary(CommandContext ctx, Recording r) {
        Recording.Summary s = r.summary();
        ctx.print(String.format(Locale.ROOT, "^2Recording '%s'^7: %d samples, %.1f s%s", r.name, s.sampleCount(), s.durationSec(),
                r.truncated ? " ^3(truncated: sample limit reached)" : ""));
        ctx.print(String.format(Locale.ROOT, "  FPS avg ^2%.0f^7, 1%% low ^2%.0f^7, min %.0f   |   worst frame %.1f ms", s.avgFps(), s.low1PercentFps(), s.minFps(), s.maxFrameMs()));
        ctx.print(String.format(Locale.ROOT, "  Client tick avg %.2f ms, max %.2f ms   |   Server MSPT avg %s", s.avgClientTickMs(), s.maxClientTickMs(),
                Double.isNaN(s.avgMsptMs()) ? "n/a" : String.format(Locale.ROOT, "%.2f ms (max %.2f)", s.avgMsptMs(), s.maxMsptMs())));
        ctx.print(String.format(Locale.ROOT, "  Heap avg %.0f MiB, max %.0f MiB   |   Lag spikes: ^%d%d", s.avgHeapMb(), s.maxHeapMb(), s.spikeCount() > 0 ? 3 : 2, s.spikeCount()));
        printSpikes(ctx, r.spikes, 8);
    }

    private static void printSpikes(CommandContext ctx, List<Spike> spikes, int max) {
        for (int i = 0; i < Math.min(max, spikes.size()); i++) {
            ctx.print("  ^3spike^7 " + ProfilerHub.describe(spikes.get(i)));
        }
        if (spikes.size() > max) ctx.print("  ... " + (spikes.size() - max) + " more (see saved file)");
        if (!spikes.isEmpty()) ctx.print("  ^9(~ = estimate from stack sampling of the last samples, not proof of the cause)");
    }

    static final class Profiler implements ConsoleCommand {
        public String name() { return "profiler"; }
        public String usage() { return "profiler <start [name]|stop|save [name]|status|spikes|list|load <file>|compare <a> <b>>"; }
        public String description() { return "Record FPS/tick/heap, detect lag spikes, save and compare recordings"; }

        public void execute(CommandContext ctx, String[] args) {
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "start" -> {
                    String n = args.length > 1 ? args[1] : new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                    if (ProfilerHub.start(n)) ctx.print("Profiler ^2started^7 as '" + n + "'. Stop with ^4profiler stop^7. (Stack sampling is on while recording.)");
                    else ctx.warn("Profiler is already running.");
                }
                case "stop" -> {
                    Recording r = ProfilerHub.stop();
                    if (r == null) ctx.warn("Profiler is not running.");
                    else {
                        printSummary(ctx, r);
                        ctx.print("Use ^4profiler save^7 to write it to disk.");
                    }
                }
                case "status" -> {
                    if (ProfilerHub.isRunning()) ctx.print("Profiler: ^2recording^7, " + ProfilerHub.sampleCount() + " samples, " + ProfilerHub.spikeCount() + " spikes so far.");
                    else ctx.print("Profiler: idle. " + (ProfilerHub.last() == null ? "No recording in memory." : "Last recording: '" + ProfilerHub.last().name + "' (profiler save / profiler spikes)."));
                }
                case "spikes" -> {
                    Recording r = ProfilerHub.last();
                    if (r == null) ctx.warn("No finished recording in memory. Use profiler start / stop.");
                    else if (r.spikes.isEmpty()) ctx.print("No lag spikes in '" + r.name + "'.");
                    else printSpikes(ctx, r.spikes, 50);
                }
                case "save" -> {
                    Recording r = ProfilerHub.last();
                    if (r == null) { ctx.warn("Nothing to save. Use profiler start / stop first."); return; }
                    Path file = resolveRecording(args.length > 1 ? args[1] : r.name);
                    Background.run(() -> {
                        try {
                            RecordingIO.save(r, file);
                            ctx.print("Saved: ^4" + file);
                        } catch (IOException e) {
                            ctx.error("Could not save recording: " + e.getMessage());
                        }
                    });
                }
                case "list" -> Background.run(() -> {
                    Path dir = StreetPaths.profilerDir();
                    if (!Files.isDirectory(dir)) { ctx.print("No recordings yet (" + dir + ")."); return; }
                    try (Stream<Path> st = Files.list(dir)) {
                        List<Path> files = st.filter(p -> p.getFileName().toString().endsWith(".srec")).sorted().toList();
                        ctx.print("^2Recordings^7 in " + dir + ": " + files.size());
                        for (Path p : files) ctx.print("  ^4" + p.getFileName());
                    } catch (IOException e) {
                        ctx.error("Could not list recordings: " + e.getMessage());
                    }
                });
                case "load" -> {
                    if (args.length < 2) { ctx.warn("Usage: profiler load <file>"); return; }
                    Path file = resolveRecording(args[1]);
                    Background.run(() -> {
                        try {
                            printSummary(ctx, RecordingIO.load(file));
                        } catch (IOException e) {
                            ctx.error("Could not load " + file.getFileName() + ": " + e.getMessage());
                        }
                    });
                }
                case "compare" -> {
                    if (args.length < 3) { ctx.warn("Usage: profiler compare <fileA> <fileB>"); return; }
                    Path a = resolveRecording(args[1]), b = resolveRecording(args[2]);
                    Background.run(() -> {
                        try {
                            String text = RecordingComparer.toText(RecordingIO.load(a), RecordingIO.load(b));
                            for (String line : text.split("\n")) ctx.print(line);
                        } catch (IOException e) {
                            ctx.error("Could not compare: " + e.getMessage());
                        }
                    });
                }
                default -> ctx.warn("Usage: ^4" + usage());
            }
        }
    }

    static final class Memory implements ConsoleCommand {
        public String name() { return "memory"; }
        public String usage() { return "memory"; }
        public String description() { return "Heap usage, GC counters and a (heuristic) memory trend check"; }

        public void execute(CommandContext ctx, String[] args) {
            MemoryTrendDetector d = MemoryHub.detector();
            double used = d.usedMb(), max = d.maxMb();
            if (Double.isNaN(used)) { ctx.warn("No memory sample yet, try again in a second."); return; }
            ctx.print(String.format(Locale.ROOT, "Heap: ^2%.0f MiB^7 used / %s   |   GC runs: %d, total GC time: %d ms (JVM counters)",
                    used, Double.isNaN(max) ? "max n/a" : String.format(Locale.ROOT, "%.0f MiB", max), MemoryHub.gcCount(), MemoryHub.gcTimeMs()));
            MemoryTrendDetector.Report r = d.report();
            switch (r.verdict()) {
                case INSUFFICIENT_DATA -> ctx.print("Trend: ^9not enough data yet^7 - " + r.explanation());
                case STABLE -> ctx.print("Trend: ^2stable^7 (" + r.floorPoints() + " post-GC points). " + r.explanation());
                case RISING_UNCONFIRMED -> ctx.warn("Trend: rising (unconfirmed). " + r.explanation());
            }
            List<MemoryTrendDetector.Jump> jumps = d.jumps();
            if (!jumps.isEmpty()) {
                ctx.print("Sudden heap jumps without a GC in between: " + jumps.size() + " (latest +" + String.format(Locale.ROOT, "%.0f", jumps.get(jumps.size() - 1).deltaMb()) + " MiB)");
            }
            ctx.print("^9Post-GC level after young collections is only an approximation of live memory.");
        }
    }

    static final class Crashlog implements ConsoleCommand {
        private static final int MAX_CRASH_BYTES = 1 << 20;

        public String name() { return "crashlog"; }
        public String usage() { return "crashlog [log]"; }
        public String description() { return "Analyze the newest crash report (or 'log': error groups in latest.log)"; }

        public void execute(CommandContext ctx, String[] args) {
            boolean log = args.length > 0 && args[0].equalsIgnoreCase("log");
            Background.run(() -> {
                try {
                    if (log) analyzeLog(ctx);
                    else analyzeNewestCrash(ctx);
                } catch (IOException | RuntimeException e) {
                    ctx.error("Crash analysis failed: " + e);
                }
            });
        }

        private static void analyzeNewestCrash(CommandContext ctx) throws IOException {
            Path dir = StreetPaths.gameDir().resolve("crash-reports");
            if (!Files.isDirectory(dir)) { ctx.warn("No crash-reports folder at " + dir); return; }
            Path newest;
            try (Stream<Path> st = Files.list(dir)) {
                newest = st.filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
            }
            if (newest == null) { ctx.warn("No crash reports in " + dir); return; }
            byte[] all = Files.readAllBytes(newest);
            String text = new String(all, 0, Math.min(all.length, MAX_CRASH_BYTES), StandardCharsets.UTF_8);
            ctx.print("Analyzing ^4" + newest.getFileName());
            for (String line : new CrashAnalyzer(SamplerHub.resolver()).analyze(text).toText().split("\n")) {
                if (!line.isEmpty()) ctx.print(line);
            }
            ctx.print("^9Heuristic: 'possible cause' is a guess from package names, never a confirmed diagnosis.");
        }

        private static void analyzeLog(CommandContext ctx) throws IOException {
            Path file = StreetPaths.gameDir().resolve("logs").resolve("latest.log");
            if (!Files.exists(file)) { ctx.warn("No latest.log at " + file); return; }
            List<String> lines = new LogTailer(file, 4 << 20, 2L << 20).poll();
            List<CrashAnalyzer.ErrorGroup> groups = new CrashAnalyzer(SamplerHub.resolver()).groupErrors(String.join("\n", lines));
            if (groups.isEmpty()) { ctx.print("No exception stack traces in the last part of latest.log."); return; }
            ctx.print("^2Error groups^7 in latest.log (last ~2 MB): " + groups.size());
            List<CrashAnalyzer.ErrorGroup> top = new ArrayList<>(groups.subList(0, Math.min(10, groups.size())));
            for (CrashAnalyzer.ErrorGroup g : top) {
                ctx.print("  ^1" + g.count() + "x^7 " + g.signature() + (g.location() == null ? "" : " ^9(" + g.location() + ")")
                        + (g.suspect() == null ? "" : " ^3possible: " + g.suspect() + " (unconfirmed)"));
            }
        }
    }
}
