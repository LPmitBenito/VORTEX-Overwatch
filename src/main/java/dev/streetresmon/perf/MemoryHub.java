package dev.streetresmon.perf;

import dev.streetresmon.core.memory.MemoryTrendDetector;
import dev.streetresmon.core.memory.MemoryTrendDetector.MemSample;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/** Feeds the memory trend detector once per second from JMX. Safe to call every tick from any thread. */
public final class MemoryHub {
    private static final MemoryTrendDetector DETECTOR = MemoryTrendDetector.defaults();
    private static long lastNs;
    private static long gcCount, gcTimeMs;

    private MemoryHub() {}

    public static synchronized void tick() {
        long now = System.nanoTime();
        if (lastNs != 0L && now - lastNs < 1_000_000_000L) return;
        lastNs = now;
        MemoryUsage u = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long c = 0, t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.getCollectionCount() > 0) c += b.getCollectionCount();
            if (b.getCollectionTime() > 0) t += b.getCollectionTime();
        }
        gcCount = c;
        gcTimeMs = t;
        DETECTOR.add(new MemSample(System.currentTimeMillis(), u.getUsed(), u.getMax(), c, t));
    }

    public static MemoryTrendDetector detector() { return DETECTOR; }

    public static synchronized long gcCount() { return gcCount; }

    public static synchronized long gcTimeMs() { return gcTimeMs; }
}
