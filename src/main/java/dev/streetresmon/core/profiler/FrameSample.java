package dev.streetresmon.core.profiler;

/** One profiler sample. Unavailable values are NaN. */
public record FrameSample(long timeMs, double fps, double frameMs, double clientTickMs, double serverMspt,
                          double heapUsedMb, double cpuLoad, long gcCount, long gcTimeMs) {}
