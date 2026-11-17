package dev.streetresmon.core.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Central module registry. A failing module never prevents the others from starting. */
public final class ModuleRegistry {
    private final Map<String, StreetModule> modules = new LinkedHashMap<>();
    private final List<StreetModule> started = new ArrayList<>();

    public synchronized void register(StreetModule m) {
        if (modules.containsKey(m.id())) throw new IllegalArgumentException("Duplicate module id: " + m.id());
        modules.put(m.id(), m);
    }

    public synchronized Optional<StreetModule> get(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    public synchronized List<StreetModule> all() {
        return new ArrayList<>(modules.values());
    }

    public synchronized boolean isStarted(String id) {
        return started.stream().anyMatch(m -> m.id().equals(id));
    }

    /**
     * Starts every module that fits {@code current} (CLIENT or SERVER) and is in {@code enabledIds}.
     * @return module id -> failure for modules whose start() threw
     */
    public synchronized Map<String, Throwable> startAll(Side current, Set<String> enabledIds) {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (StreetModule m : modules.values()) {
            if (!enabledIds.contains(m.id())) continue;
            if (m.side() != Side.BOTH && m.side() != current) continue;
            if (started.contains(m)) continue;
            try {
                m.start();
                started.add(m);
            } catch (Throwable t) {
                failures.put(m.id(), t);
            }
        }
        return failures;
    }

    public synchronized Map<String, Throwable> stopAll() {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (int i = started.size() - 1; i >= 0; i--) {
            StreetModule m = started.get(i);
            try {
                m.stop();
            } catch (Throwable t) {
                failures.put(m.id(), t);
            }
        }
        started.clear();
        return failures;
    }
}
