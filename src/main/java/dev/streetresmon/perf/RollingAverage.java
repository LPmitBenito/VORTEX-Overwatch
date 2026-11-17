package dev.streetresmon.perf;

/** Fixed-size rolling average, thread-safe, allocation-free. */
final class RollingAverage {
    private final double[] values;
    private int index;
    private int count;
    private double sum;

    RollingAverage(int size) {
        this.values = new double[size];
    }

    synchronized void add(double v) {
        if (count == values.length) sum -= values[index]; else count++;
        values[index] = v;
        sum += v;
        index = (index + 1) % values.length;
    }

    synchronized double average() {
        return count == 0 ? 0.0 : sum / count;
    }

    synchronized boolean hasData() {
        return count > 0;
    }
}
