package dev.streetresmon.core.pool;

import dev.streetresmon.core.Metric;
import dev.streetresmon.core.module.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Registry of named gauges (entities, block entities, chunks...). A gauge supplier returns a count or null
 * when the value cannot be determined; exceptions are caught. Nothing is shown unless a supplier delivers it.
 * Suppliers must only touch game state on the thread that is allowed to (caller decides when to read).
 */
public final class PoolRegistry {
    public enum Category { ENTITY, BLOCK_ENTITY, CHUNK, PARTICLE, RENDER, OTHER }

    public record Gauge(String id, String label, Category category, Side side, Supplier<Long> supplier, String source) {}

    public record Reading(Gauge gauge, Metric metric) {}

    private final List<Gauge> gauges = new ArrayList<>();

    public synchronized void register(Gauge g) {
        for (Gauge x : gauges) if (x.id().equals(g.id())) throw new IllegalArgumentException("Duplicate gauge: " + g.id());
        gauges.add(g);
    }

    public synchronized List<Reading> readAll(Side current) {
        List<Reading> out = new ArrayList<>();
        for (Gauge g : gauges) {
            if (g.side() != Side.BOTH && g.side() != current) continue;
            Metric m;
            try {
                Long v = g.supplier().get();
                m = v == null ? Metric.unavailable("source did not report a value") : Metric.measured(v, "");
            } catch (RuntimeException e) {
                m = Metric.unavailable("read failed: " + e.getClass().getSimpleName());
            }
            out.add(new Reading(g, m));
        }
        return out;
    }
}
