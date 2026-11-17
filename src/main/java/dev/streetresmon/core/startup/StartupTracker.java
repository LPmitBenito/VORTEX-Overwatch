package dev.streetresmon.core.startup;

import dev.streetresmon.core.Metric;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Records durations of startup phases that the caller reports and problems seen during startup. */
public final class StartupTracker {
    public record Phase(String name, String owner, long durationNanos) {
        public double durationMs() { return durationNanos / 1e6; }
    }

    public record Problem(String level, String message) {}

    private final LongSupplier nanoClock;
    private final Map<String, Long> open = new HashMap<>();
    private final List<Phase> phases = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();

    public StartupTracker(LongSupplier nanoClock) { this.nanoClock = nanoClock; }

    public static StartupTracker system() { return new StartupTracker(System::nanoTime); }

    public synchronized void begin(String name) { open.put(name, nanoClock.getAsLong()); }

    /** @return false if no matching begin() exists */
    public synchronized boolean end(String name, String owner) {
        Long start = open.remove(name);
        if (start == null) return false;
        phases.add(new Phase(name, owner, nanoClock.getAsLong() - start));
        return true;
    }

    public synchronized void record(String name, String owner, long durationNanos) {
        phases.add(new Phase(name, owner, durationNanos));
    }

    public synchronized void problem(String level, String message) {
        if (problems.size() < 500) problems.add(new Problem(level, message));
    }

    public synchronized List<Phase> sortedByDuration() {
        List<Phase> l = new ArrayList<>(phases);
        l.sort((a, b) -> Long.compare(b.durationNanos(), a.durationNanos()));
        return l;
    }

    public synchronized List<Phase> slowPhases(double thresholdMs) {
        List<Phase> l = new ArrayList<>();
        for (Phase p : sortedByDuration()) if (p.durationMs() >= thresholdMs) l.add(p);
        return l;
    }

    public synchronized double totalTrackedMs() {
        double s = 0;
        for (Phase p : phases) s += p.durationMs();
        return s;
    }

    public synchronized List<Problem> problems() { return new ArrayList<>(problems); }

    public synchronized long countProblems(String level) {
        return problems.stream().filter(p -> p.level().equalsIgnoreCase(level)).count();
    }

    /** Time since JVM start (measured, JVM-provided). This is NOT the same as "time until the main menu". */
    public static Metric jvmUptime() {
        return Metric.measured(ManagementFactory.getRuntimeMXBean().getUptime(), "ms");
    }
}
