package dev.streetresmon.core.profiler;

/** A detected lag spike. {@code hint} is an ESTIMATE (e.g. top sampled owner) or empty. */
public record Spike(long timeMs, String kind, double valueMs, double baselineMs, String hint) {}
