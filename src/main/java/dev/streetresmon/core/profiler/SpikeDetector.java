package dev.streetresmon.core.profiler;

import dev.streetresmon.core.util.RingBuffer;
import dev.streetresmon.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A value is a spike if it exceeds both an absolute floor and {@code factor} x the rolling median.
 * One cooldown per kind prevents a single stall from being reported many times.
 * The hint supplier is only called when a spike is actually found (keeps the common path cheap).
 */
public final class SpikeDetector {
    public static final String FRAME = "FRAME";
    public static final String CLIENT_TICK = "CLIENT_TICK";
    public static final String SERVER_TICK = "SERVER_TICK";

    private final double factor;
    private final double minAbsMs;
    private final long cooldownMs;
    private final int minHistory;
    private final RingBuffer frame, client, server;
    private long lastFrame = Long.MIN_VALUE / 2, lastClient = Long.MIN_VALUE / 2, lastServer = Long.MIN_VALUE / 2;

    public SpikeDetector(int window, double factor, double minAbsMs, long cooldownMs) {
        this.factor = factor;
        this.minAbsMs = minAbsMs;
        this.cooldownMs = cooldownMs;
        this.minHistory = Math.min(30, window);
        frame = new RingBuffer(window);
        client = new RingBuffer(window);
        server = new RingBuffer(window);
    }

    public static SpikeDetector defaults() { return new SpikeDetector(120, 3.0, 50.0, 500); }

    public synchronized List<Spike> onSample(FrameSample s, Supplier<String> hint) {
        List<Spike> out = new ArrayList<>(1);
        if (check(FRAME, s.timeMs(), s.frameMs(), frame, lastFrame)) { out.add(new Spike(s.timeMs(), FRAME, s.frameMs(), base, "")); lastFrame = s.timeMs(); }
        if (check(CLIENT_TICK, s.timeMs(), s.clientTickMs(), client, lastClient)) { out.add(new Spike(s.timeMs(), CLIENT_TICK, s.clientTickMs(), base, "")); lastClient = s.timeMs(); }
        if (check(SERVER_TICK, s.timeMs(), s.serverMspt(), server, lastServer)) { out.add(new Spike(s.timeMs(), SERVER_TICK, s.serverMspt(), base, "")); lastServer = s.timeMs(); }
        if (out.isEmpty() || hint == null) return out;
        String h = String.valueOf(hint.get());
        List<Spike> withHint = new ArrayList<>(out.size());
        for (Spike sp : out) withHint.add(new Spike(sp.timeMs(), sp.kind(), sp.valueMs(), sp.baselineMs(), h));
        return withHint;
    }

    private double base; // baseline of the last check() call that returned true

    private boolean check(String kind, long t, double v, RingBuffer buf, long last) {
        if (Double.isNaN(v)) return false;
        boolean spike = false;
        if (buf.size() >= minHistory) {
            double b = Stats.median(buf.toArray());
            if (v > Math.max(minAbsMs, factor * b) && t - last >= cooldownMs) {
                spike = true;
                base = b;
            }
        }
        buf.add(v);
        return spike;
    }
}
