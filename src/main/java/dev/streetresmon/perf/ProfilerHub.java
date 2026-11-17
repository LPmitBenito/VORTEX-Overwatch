package dev.streetresmon.perf;

import dev.streetresmon.core.profiler.FrameSample;
import dev.streetresmon.core.profiler.ProfilerSession;
import dev.streetresmon.core.profiler.Recording;
import dev.streetresmon.core.profiler.Spike;
import dev.streetresmon.core.profiler.SpikeDetector;
import dev.streetresmon.core.resmon.ModSampleTable;

import java.util.OptionalDouble;

/**
 * Connects the profiler core to the game: one sample per client tick (20/s). frameMs is the WORST frame since the
 * previous sample, so a single long frame is not averaged away. Dedicated server: no client ticks, so no samples
 * (server profiling is a later step). All heavy work (file I/O) is done elsewhere, never here.
 */
public final class ProfilerHub {
    private static final ProfilerSession SESSION = new ProfilerSession(36_000, SpikeDetector.defaults());
    private static volatile boolean running;
    private static volatile Recording last;

    // render/client thread only
    private static long lastFrameNs, windowStartNs;
    private static double maxFrameMs;
    private static int frames;

    private ProfilerHub() {}

    public static boolean isRunning() { return running; }

    public static Recording last() { return last; }

    public static int sampleCount() { return SESSION.sampleCount(); }

    public static int spikeCount() { return SESSION.spikeCount(); }

    public static boolean start(String name) {
        if (!SESSION.start(name, System.currentTimeMillis())) return false;
        lastFrameNs = 0;
        windowStartNs = 0;
        maxFrameMs = 0;
        frames = 0;
        running = true;
        SamplerHub.acquire("profiler");
        return true;
    }

    /** @return the finished recording or null if nothing was running */
    public static Recording stop() {
        running = false;
        Recording r = SESSION.stop(System.currentTimeMillis());
        SamplerHub.release("profiler");
        if (r != null) last = r;
        return r;
    }

    public static void onFrame(long nowNs) {
        if (!running) return;
        if (lastFrameNs != 0L) {
            double ms = (nowNs - lastFrameNs) / 1_000_000.0;
            if (ms > maxFrameMs) maxFrameMs = ms;
            frames++;
        }
        lastFrameNs = nowNs;
    }

    public static void onClientTick() {
        if (!running) return;
        long now = System.nanoTime();
        if (windowStartNs == 0L) {
            windowStartNs = now;
            return;
        }
        double windowMs = (now - windowStartNs) / 1_000_000.0;
        double fps = frames > 0 ? frames * 1000.0 / windowMs : 0.0;
        double frameMs = frames > 0 ? maxFrameMs : windowMs; // no frame finished in this window = a stall
        windowStartNs = now;
        maxFrameMs = 0;
        frames = 0;

        PerformanceMonitor pm = PerformanceMonitor.get();
        pm.refresh();
        OptionalDouble cpu = pm.processCpuPercent();
        FrameSample s = new FrameSample(System.currentTimeMillis(), fps, frameMs,
                pm.lastClientTickMs(), pm.lastServerTickMs(),
                pm.heapUsedBytes() / 1048576.0, cpu.isPresent() ? cpu.getAsDouble() / 100.0 : Double.NaN,
                MemoryHub.gcCount(), MemoryHub.gcTimeMs());
        SESSION.add(s, ProfilerHub::spikeHint);
    }

    private static String spikeHint() {
        ModSampleTable c = SamplerHub.clientTable();
        ModSampleTable sv = SamplerHub.serverTable();
        String a = c == null ? "" : c.recentSummary(16, 3);
        String b = sv == null ? "" : sv.recentSummary(16, 3);
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append("~client thread: ").append(a);
        if (!b.isEmpty()) sb.append(sb.length() > 0 ? "; " : "").append("~server thread: ").append(b);
        return sb.toString();
    }

    public static String describe(Spike s) {
        return String.format(java.util.Locale.ROOT, "%s %.1f ms (baseline %.1f ms)%s", s.kind(), s.valueMs(), s.baselineMs(),
                s.hint().isEmpty() ? "" : "  " + s.hint());
    }
}
