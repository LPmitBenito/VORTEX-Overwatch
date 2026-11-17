package dev.streetresmon.core.resmon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Exact timing aggregation for code sections that the caller wraps with nanoTime (event handlers, commands...).
 * The number of distinct keys is capped so a misbehaving caller cannot grow memory without bound.
 */
public final class EventTimingTable {
    public static final String OVERFLOW = "(other)";

    public record Row(String event, String owner, long count, double totalMs, double avgMs, double maxMs) {}

    private static final class Acc {
        final LongAdder count = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
    }

    private final int maxKeys;
    private final Map<String, Acc> map = new ConcurrentHashMap<>();

    public EventTimingTable(int maxKeys) { this.maxKeys = maxKeys; }

    public void record(String event, String owner, long nanos) {
        String key = event + "\u0000" + owner;
        Acc a = map.get(key);
        if (a == null) {
            if (map.size() >= maxKeys) {
                key = OVERFLOW + "\u0000" + OVERFLOW;
            }
            a = map.computeIfAbsent(key, k -> new Acc());
        }
        a.count.increment();
        a.totalNanos.add(nanos);
        a.maxNanos.accumulateAndGet(nanos, Math::max);
    }

    public int distinctKeys() { return map.size(); }

    public List<Row> snapshot(Comparator<Row> order) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : map.entrySet()) {
            String[] k = e.getKey().split("\u0000", 2);
            Acc a = e.getValue();
            long c = a.count.sum();
            double total = a.totalNanos.sum() / 1e6;
            rows.add(new Row(k[0], k.length > 1 ? k[1] : "", c, total, c == 0 ? 0 : total / c, a.maxNanos.get() / 1e6));
        }
        rows.sort(order);
        return rows;
    }

    public List<Row> byTotalTime() {
        return snapshot(Comparator.comparingDouble(Row::totalMs).reversed());
    }

    public void reset() { map.clear(); }
}
