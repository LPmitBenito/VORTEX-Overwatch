package dev.streetresmon.core.profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Collects samples while running. Memory is bounded by {@code maxSamples}; overflow sets the truncated flag. */
public final class ProfilerSession {
    private final int maxSamples;
    private final SpikeDetector detector;
    private boolean running;
    private boolean truncated;
    private String name = "";
    private long startedAt;
    private List<FrameSample> samples = new ArrayList<>();
    private List<Spike> spikes = new ArrayList<>();

    public ProfilerSession(int maxSamples, SpikeDetector detector) {
        this.maxSamples = maxSamples;
        this.detector = detector;
    }

    public synchronized boolean start(String name, long nowMs) {
        if (running) return false;
        running = true;
        truncated = false;
        this.name = name;
        this.startedAt = nowMs;
        samples = new ArrayList<>();
        spikes = new ArrayList<>();
        return true;
    }

    public synchronized boolean isRunning() { return running; }

    public synchronized int sampleCount() { return samples.size(); }

    public synchronized int spikeCount() { return spikes.size(); }

    public synchronized List<Spike> add(FrameSample s, Supplier<String> hint) {
        if (!running) return List.of();
        if (samples.size() >= maxSamples) {
            truncated = true;
            return List.of();
        }
        samples.add(s);
        List<Spike> found = detector.onSample(s, hint);
        spikes.addAll(found);
        return found;
    }

    /** @return the recording, or null if the session was not running */
    public synchronized Recording stop(long nowMs) {
        if (!running) return null;
        running = false;
        Recording r = new Recording(name, startedAt, nowMs, truncated, samples, spikes);
        samples = new ArrayList<>();
        spikes = new ArrayList<>();
        return r;
    }
}
