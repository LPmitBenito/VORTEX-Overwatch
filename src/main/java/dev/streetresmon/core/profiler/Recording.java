package dev.streetresmon.core.profiler;

import dev.streetresmon.core.util.Stats;

import java.util.List;

/** An immutable-by-convention finished recording. */
public final class Recording {
    public record Summary(int sampleCount, double durationSec, double avgFps, double low1PercentFps, double minFps,
                          double avgFrameMs, double maxFrameMs, double avgClientTickMs, double maxClientTickMs,
                          double avgMsptMs, double maxMsptMs, double avgHeapMb, double maxHeapMb, int spikeCount) {}

    public final String name;
    public final long startedAtMs;
    public final long endedAtMs;
    public final boolean truncated;
    public final List<FrameSample> samples;
    public final List<Spike> spikes;

    public Recording(String name, long startedAtMs, long endedAtMs, boolean truncated,
                     List<FrameSample> samples, List<Spike> spikes) {
        this.name = name;
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.truncated = truncated;
        this.samples = List.copyOf(samples);
        this.spikes = List.copyOf(spikes);
    }

    private double[] col(java.util.function.ToDoubleFunction<FrameSample> f) {
        double[] a = new double[samples.size()];
        for (int i = 0; i < a.length; i++) a[i] = f.applyAsDouble(samples.get(i));
        return a;
    }

    public double[] fps() { return col(FrameSample::fps); }
    public double[] frameMs() { return col(FrameSample::frameMs); }
    public double[] clientTickMs() { return col(FrameSample::clientTickMs); }
    public double[] heapMb() { return col(FrameSample::heapUsedMb); }
    public double[] cpuLoad() { return col(FrameSample::cpuLoad); }
    public double[] gcCount() { return col(s -> (double) s.gcCount()); }

    public Summary summary() {
        double[] fps = fps(), fm = frameMs(), ct = clientTickMs(), ms = col(FrameSample::serverMspt), hp = heapMb();
        return new Summary(samples.size(), Math.max(0, (endedAtMs - startedAtMs) / 1000.0),
                Stats.avg(fps), Stats.lowFractionAvg(fps, 0.01), Stats.min(fps),
                Stats.avg(fm), Stats.max(fm), Stats.avg(ct), Stats.max(ct),
                Stats.avg(ms), Stats.max(ms), Stats.avg(hp), Stats.max(hp), spikes.size());
    }
}
