package dev.streetresmon.core.net;

import dev.streetresmon.core.Metric;
import dev.streetresmon.core.util.RingBuffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.LongAdder;

/**
 * Network counters. This class only COUNTS what a data source reports; it never invents numbers.
 * If no traffic was ever reported the snapshot says so (trafficMeasured=false). Ping older than
 * {@code pingStaleMs} is reported as unavailable.
 */
public final class NetStats {
    public record Snapshot(long timeMs, boolean connected, boolean trafficMeasured,
                           double packetsInPerSec, double packetsOutPerSec,
                           double bytesInPerSec, double bytesOutPerSec, Metric pingMs) {}

    private final LongAdder packetsIn = new LongAdder(), packetsOut = new LongAdder();
    private final LongAdder bytesIn = new LongAdder(), bytesOut = new LongAdder();
    private volatile boolean trafficSeen;
    private volatile double lastPing = Double.NaN;
    private volatile long lastPingAt = Long.MIN_VALUE / 2;
    private final long pingStaleMs;

    private boolean connected;
    private int disconnects;
    private int errors;
    private final Deque<String> errorLog = new ArrayDeque<>();
    private long lastSampleAt = -1, lastPIn, lastPOut, lastBIn, lastBOut;

    public final RingBuffer inBytesHistory, outBytesHistory, pingHistory;

    public NetStats(int historySize, long pingStaleMs) {
        this.pingStaleMs = pingStaleMs;
        inBytesHistory = new RingBuffer(historySize);
        outBytesHistory = new RingBuffer(historySize);
        pingHistory = new RingBuffer(historySize);
    }

    public void recordIn(long bytes) { trafficSeen = true; packetsIn.increment(); bytesIn.add(bytes); }

    public void recordOut(long bytes) { trafficSeen = true; packetsOut.increment(); bytesOut.add(bytes); }

    public void recordPing(double ms, long nowMs) { lastPing = ms; lastPingAt = nowMs; }

    public synchronized void setConnected(boolean c) {
        if (connected && !c) disconnects++;
        connected = c;
    }

    public synchronized void recordError(String message) {
        errors++;
        errorLog.addLast(message);
        while (errorLog.size() > 50) errorLog.removeFirst();
    }

    public synchronized int disconnects() { return disconnects; }

    public synchronized int errors() { return errors; }

    public synchronized java.util.List<String> recentErrors() { return new java.util.ArrayList<>(errorLog); }

    public long totalBytesIn() { return bytesIn.sum(); }

    public long totalBytesOut() { return bytesOut.sum(); }

    /** Call about once per second from a background/tick thread. First call only establishes the baseline. */
    public synchronized Snapshot sample(long nowMs) {
        long pi = packetsIn.sum(), po = packetsOut.sum(), bi = bytesIn.sum(), bo = bytesOut.sum();
        double dt = lastSampleAt < 0 ? 0 : (nowMs - lastSampleAt) / 1000.0;
        double rpi = dt > 0 ? (pi - lastPIn) / dt : 0, rpo = dt > 0 ? (po - lastPOut) / dt : 0;
        double rbi = dt > 0 ? (bi - lastBIn) / dt : 0, rbo = dt > 0 ? (bo - lastBOut) / dt : 0;
        lastSampleAt = nowMs; lastPIn = pi; lastPOut = po; lastBIn = bi; lastBOut = bo;

        Metric ping;
        if (Double.isNaN(lastPing)) ping = Metric.unavailable("no ping source connected");
        else if (nowMs - lastPingAt > pingStaleMs) ping = Metric.unavailable("ping value is stale");
        else ping = Metric.measured(lastPing, "ms");

        if (dt > 0) {
            inBytesHistory.add(rbi);
            outBytesHistory.add(rbo);
            pingHistory.add(ping.available() ? ping.value() : Double.NaN);
        }
        return new Snapshot(nowMs, connected, trafficSeen, rpi, rpo, rbi, rbo, ping);
    }
}
