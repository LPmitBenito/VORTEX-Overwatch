package dev.streetresmon.core.memory;

import dev.streetresmon.core.util.RingBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks at the heap level right after garbage collections (an approximation of the live set) and fits a
 * straight line through those points. A rising line is only a hint ("RISING_UNCONFIRMED"); this class can
 * never prove a memory leak. It also reports sudden jumps in used heap without a GC in between.
 */
public final class MemoryTrendDetector {
    public record MemSample(long timeMs, long usedBytes, long maxBytes, long gcCount, long gcTimeMs) {}

    public enum Verdict { INSUFFICIENT_DATA, STABLE, RISING_UNCONFIRMED }

    public record Report(Verdict verdict, double slopeMbPerMin, double r2, int floorPoints, String explanation) {}

    public record Jump(long timeMs, double deltaMb) {}

    private static final double MB = 1024.0 * 1024.0;

    private final long minWindowMs;
    private final int minPoints;
    private final double minSlopeMbPerMin;
    private final double minR2;
    private final double jumpMb;
    private final int maxFloors;

    private final List<double[]> floors = new ArrayList<>(); // {timeMs, usedMb}
    private final List<Jump> jumps = new ArrayList<>();
    public final RingBuffer usedMbHistory;
    private MemSample last;
    private long maxBytes;

    public MemoryTrendDetector(long minWindowMs, int minPoints, double minSlopeMbPerMin, double minR2,
                               double jumpMb, int historySize) {
        this.minWindowMs = minWindowMs;
        this.minPoints = minPoints;
        this.minSlopeMbPerMin = minSlopeMbPerMin;
        this.minR2 = minR2;
        this.jumpMb = jumpMb;
        this.maxFloors = 600;
        this.usedMbHistory = new RingBuffer(historySize);
    }

    public static MemoryTrendDetector defaults() {
        return new MemoryTrendDetector(5 * 60_000L, 6, 2.0, 0.6, 256.0, 3600);
    }

    public synchronized void add(MemSample s) {
        usedMbHistory.add(s.usedBytes() / MB);
        maxBytes = s.maxBytes();
        if (last != null) {
            if (s.gcCount() > last.gcCount()) {
                floors.add(new double[] {s.timeMs(), s.usedBytes() / MB});
                if (floors.size() > maxFloors) floors.remove(0);
            } else {
                double d = (s.usedBytes() - last.usedBytes()) / MB;
                if (d >= jumpMb) {
                    jumps.add(new Jump(s.timeMs(), d));
                    if (jumps.size() > 50) jumps.remove(0);
                }
            }
        }
        last = s;
    }

    public synchronized List<Jump> jumps() { return new ArrayList<>(jumps); }

    public synchronized double usedMb() { return last == null ? Double.NaN : last.usedBytes() / MB; }

    public synchronized double maxMb() { return maxBytes <= 0 ? Double.NaN : maxBytes / MB; }

    public synchronized Report report() {
        int n = floors.size();
        if (n < minPoints) {
            return new Report(Verdict.INSUFFICIENT_DATA, Double.NaN, Double.NaN, n,
                    "Need at least " + minPoints + " post-GC points, have " + n + ".");
        }
        double spanMs = floors.get(n - 1)[0] - floors.get(0)[0];
        if (spanMs < minWindowMs) {
            return new Report(Verdict.INSUFFICIENT_DATA, Double.NaN, Double.NaN, n,
                    "Observation window too short (" + (spanMs / 1000) + " s, need " + (minWindowMs / 1000) + " s).");
        }
        double t0 = floors.get(0)[0];
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        for (double[] f : floors) {
            double x = (f[0] - t0) / 60_000.0, y = f[1];
            sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y;
        }
        double den = n * sxx - sx * sx;
        double slope = den == 0 ? 0 : (n * sxy - sx * sy) / den;
        double varY = n * syy - sy * sy;
        double r2 = (den == 0 || varY == 0) ? 0 : Math.pow(n * sxy - sx * sy, 2) / (den * varY);
        if (slope >= minSlopeMbPerMin && r2 >= minR2) {
            return new Report(Verdict.RISING_UNCONFIRMED, slope, r2, n,
                    String.format(java.util.Locale.ROOT,
                            "Heap after GC rises ~%.1f MB/min (R2=%.2f). This is only a hint; it does not prove a leak.", slope, r2));
        }
        return new Report(Verdict.STABLE, slope, r2, n, "No sustained rise of the post-GC heap level observed.");
    }
}
