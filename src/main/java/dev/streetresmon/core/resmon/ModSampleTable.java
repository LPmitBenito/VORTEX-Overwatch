package dev.streetresmon.core.resmon;

import dev.streetresmon.core.owner.ModOwnerResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregates stack samples per owner. This is a SAMPLING ESTIMATE, not an exact per-mod CPU measurement:
 * <ul>
 *   <li>self      = innermost frame that is not JVM/library code (JDK calls are charged to the caller)</li>
 *   <li>inclusive = every owner that appears anywhere in the stack</li>
 * </ul>
 */
public final class ModSampleTable {
    public static final String UNATTRIBUTED = "(unattributed)";

    public record Row(String ownerId, long selfSamples, long inclusiveSamples,
                      double selfPercent, double inclusivePercent,
                      double estSelfMsPerSec, double estInclusiveMsPerSec) {}

    private final ModOwnerResolver resolver;
    private final long intervalMs;
    private final Map<String, LongAdder> self = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> incl = new ConcurrentHashMap<>();
    private final LongAdder busy = new LongAdder();
    private final LongAdder idle = new LongAdder();
    private volatile long sinceNanos;
    private static final int RECENT = 64;
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

    public ModSampleTable(ModOwnerResolver resolver, long intervalMs, long nowNanos) {
        this.resolver = resolver;
        this.intervalMs = intervalMs;
        this.sinceNanos = nowNanos;
    }

    /** A sample in which the thread was not running Java code (waiting, sleeping, in a native call). */
    public void recordIdle() { idle.increment(); }

    public void record(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0 || stack[0].isNativeMethod()) {
            idle.increment();
            return;
        }
        busy.increment();
        String selfOwner = null;
        Set<String> seen = new HashSet<>(8);
        for (StackTraceElement f : stack) {
            String o = resolver.resolve(f.getClassName());
            if (ModOwnerResolver.JVM.equals(o) || ModOwnerResolver.LIBRARY.equals(o)) continue;
            if (o == null) o = UNATTRIBUTED;
            if (selfOwner == null) selfOwner = o;
            seen.add(o);
        }
        if (selfOwner == null) { // only JVM/library frames on the stack
            selfOwner = ModOwnerResolver.JVM;
            seen.add(selfOwner);
        }
        synchronized (recent) {
            recent.addLast(selfOwner);
            if (recent.size() > RECENT) recent.removeFirst();
        }
        self.computeIfAbsent(selfOwner, k -> new LongAdder()).increment();
        for (String o : seen) incl.computeIfAbsent(o, k -> new LongAdder()).increment();
    }

    /**
     * Short text about the most recent busy samples, e.g. "modA 7/16, minecraft 5/16". Used as a spike hint.
     * It is an estimate (sampling), not proof of what caused a spike.
     */
    public String recentSummary(int lastN, int top) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int n;
        synchronized (recent) {
            java.util.Iterator<String> it = recent.descendingIterator();
            int i = 0;
            while (it.hasNext() && i < lastN) { counts.merge(it.next(), 1, Integer::sum); i++; }
            n = i;
        }
        if (n == 0) return "";
        java.util.List<java.util.Map.Entry<String, Integer>> es = new java.util.ArrayList<>(counts.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(top, es.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(es.get(i).getKey()).append(' ').append(es.get(i).getValue()).append('/').append(n);
        }
        return sb.toString();
    }

    public long busySamples() { return busy.sum(); }

    public long idleSamples() { return idle.sum(); }

    /** Rows sorted by self samples, descending. */
    public List<Row> snapshot(long nowNanos) {
        double elapsedMs = Math.max(1.0, (nowNanos - sinceNanos) / 1_000_000.0);
        long b = Math.max(1, busy.sum());
        Set<String> keys = new HashSet<>(self.keySet());
        keys.addAll(incl.keySet());
        List<Row> rows = new ArrayList<>(keys.size());
        for (String k : keys) {
            long s = self.getOrDefault(k, new LongAdder()).sum();
            long i = incl.getOrDefault(k, new LongAdder()).sum();
            rows.add(new Row(k, s, i, 100.0 * s / b, 100.0 * i / b,
                    s * intervalMs * 1000.0 / elapsedMs, i * intervalMs * 1000.0 / elapsedMs));
        }
        rows.sort((x, y) -> Long.compare(y.selfSamples(), x.selfSamples()));
        return rows;
    }

    public void reset(long nowNanos) {
        self.clear();
        incl.clear();
        busy.reset();
        idle.reset();
        synchronized (recent) { recent.clear(); }
        sinceNanos = nowNanos;
    }
}
