package dev.streetresmon.perf;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Collects REAL measurements only:
 *  - FPS / frame time (counted per rendered frame)
 *  - client tick time and server tick time (MSPT), measured with System.nanoTime()
 *  - process CPU load and per-thread CPU time (JMX)
 *  - JVM heap usage (JMX)
 *
 * Per-mod CPU/memory is NOT measurable with Minecraft/NeoForge and is therefore never reported.
 *
 * The cheap hooks (frame/tick) run permanently. The heavier JMX sampling in {@link #refresh()} is
 * rate limited and only called while a StreetResmon screen is open or a command asks for data.
 */
public final class PerformanceMonitor {
    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();
    private static final long SAMPLE_INTERVAL_NS = 500_000_000L;
    private static final long SERVER_STALE_NS = 3_000_000_000L;

    public record ThreadStat(String name, double cpuPercent) {}

    public static PerformanceMonitor get() {
        return INSTANCE;
    }

    // ---- frame / FPS ----
    private long frameCount;
    private long fpsWindowStart = System.nanoTime();
    private volatile double fps = -1;
    private volatile double frameMs = -1;

    // ---- ticks ----
    private final RollingAverage clientTickMs = new RollingAverage(40);
    private final RollingAverage serverTickMs = new RollingAverage(40);
    private long clientTickStart;
    private volatile long serverTickStart;
    private volatile long lastServerTickNs;
    private volatile double lastClientTickMs = Double.NaN;
    private volatile double lastServerTickMs = Double.NaN;

    // ---- JMX ----
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final com.sun.management.OperatingSystemMXBean osBean =
            ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean b ? b : null;

    private long lastSampleNs;
    private final Map<Long, String> threadNames = new HashMap<>();
    private final Map<Long, Long> lastThreadCpuNs = new HashMap<>();
    private volatile List<ThreadStat> threadStats = List.of();
    private volatile boolean threadStatsValid;
    private volatile double processCpu = -1;
    private volatile long heapUsed;
    private volatile long heapMax;

    private PerformanceMonitor() {
        try {
            if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // thread CPU time stays unavailable; reported as such
        }
    }

    // ---------- permanent, cheap hooks ----------

    public void onFrame() {
        frameCount++;
        long now = System.nanoTime();
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 500_000_000L) {
            fps = frameCount * 1_000_000_000.0 / elapsed;
            frameMs = elapsed / 1_000_000.0 / frameCount;
            frameCount = 0;
            fpsWindowStart = now;
        }
    }

    public void clientTickStart() {
        clientTickStart = System.nanoTime();
    }

    public void clientTickEnd() {
        if (clientTickStart != 0L) {
            double ms = (System.nanoTime() - clientTickStart) / 1_000_000.0;
            clientTickMs.add(ms);
            lastClientTickMs = ms;
        }
    }

    public void serverTickStart() {
        serverTickStart = System.nanoTime();
    }

    public void serverTickEnd() {
        long now = System.nanoTime();
        long start = serverTickStart;
        if (start != 0L) {
            double ms = (now - start) / 1_000_000.0;
            serverTickMs.add(ms);
            lastServerTickMs = ms;
        }
        lastServerTickNs = now;
    }

    // ---------- accessors (empty = not available) ----------

    /** Frames per second, empty until the first measurement window finished. */
    public OptionalDouble fps() {
        double v = fps;
        return v < 0 ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    public OptionalDouble frameMs() {
        double v = frameMs;
        return v < 0 ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    public OptionalDouble clientTickMsAvg() {
        return clientTickMs.hasData() ? OptionalDouble.of(clientTickMs.average()) : OptionalDouble.empty();
    }

    /** Average server tick time. Empty when no server in this JVM ticked recently (e.g. remote server). */
    public OptionalDouble serverMspt() {
        long last = lastServerTickNs;
        if (last == 0L || System.nanoTime() - last > SERVER_STALE_NS || !serverTickMs.hasData()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(serverTickMs.average());
    }

    /** Duration of the most recent client tick, NaN before the first one. */
    public double lastClientTickMs() {
        return lastClientTickMs;
    }

    /** Duration of the most recent server tick of a server in this JVM, NaN if none ticked recently. */
    public double lastServerTickMs() {
        long last = lastServerTickNs;
        if (last == 0L || System.nanoTime() - last > SERVER_STALE_NS) return Double.NaN;
        return lastServerTickMs;
    }

    /** Process CPU load in percent of the whole machine, empty if the JVM cannot report it. */
    public OptionalDouble processCpuPercent() {
        double v = processCpu;
        return v < 0 ? OptionalDouble.empty() : OptionalDouble.of(v * 100.0);
    }

    public long heapUsedBytes() {
        return heapUsed;
    }

    public long heapMaxBytes() {
        return heapMax;
    }

    public boolean threadStatsAvailable() {
        return threadStatsValid;
    }

    public List<ThreadStat> threadStats() {
        return threadStats;
    }

    // ---------- rate limited sampling ----------

    /** Samples JMX data. Cheap to call often: does real work at most every 500 ms. */
    public synchronized void refresh() {
        long now = System.nanoTime();
        if (lastSampleNs != 0L && now - lastSampleNs < SAMPLE_INTERVAL_NS) return;
        boolean firstSample = lastSampleNs == 0L;
        long wall = firstSample ? 0L : now - lastSampleNs;
        lastSampleNs = now;

        heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        heapMax = memoryBean.getHeapMemoryUsage().getMax();
        processCpu = osBean != null ? osBean.getProcessCpuLoad() : -1;

        sampleThreads(wall, firstSample);
    }

    private void sampleThreads(long wallNs, boolean firstSample) {
        if (!threadBean.isThreadCpuTimeSupported() || !threadBean.isThreadCpuTimeEnabled()) {
            threadStatsValid = false;
            return;
        }
        long[] ids = threadBean.getAllThreadIds();
        Set<Long> alive = new HashSet<>();
        List<ThreadStat> out = new ArrayList<>(ids.length);
        for (long id : ids) {
            alive.add(id);
            long cpu = threadBean.getThreadCpuTime(id);
            if (cpu < 0) continue;
            String name = threadNames.get(id);
            if (name == null) {
                ThreadInfo info = threadBean.getThreadInfo(id);
                if (info == null) continue;
                name = info.getThreadName();
                threadNames.put(id, name);
            }
            Long prev = lastThreadCpuNs.put(id, cpu);
            if (!firstSample && prev != null && wallNs > 0) {
                out.add(new ThreadStat(name, Math.max(0.0, (cpu - prev) * 100.0 / wallNs)));
            }
        }
        threadNames.keySet().retainAll(alive);
        lastThreadCpuNs.keySet().retainAll(alive);
        if (!firstSample) {
            out.sort(Comparator.comparingDouble(ThreadStat::cpuPercent).reversed());
            threadStats = List.copyOf(out);
            threadStatsValid = true;
        }
    }
}
