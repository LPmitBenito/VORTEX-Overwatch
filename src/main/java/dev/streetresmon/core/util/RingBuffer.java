package dev.streetresmon.core.util;

/** Fixed-capacity, thread-safe ring buffer of doubles (oldest values are overwritten). */
public final class RingBuffer {
    private final double[] data;
    private int size;
    private int head; // index of next write

    public RingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.data = new double[capacity];
    }

    public synchronized void add(double v) {
        data[head] = v;
        head = (head + 1) % data.length;
        if (size < data.length) size++;
    }

    /** Oldest first. */
    public synchronized double[] toArray() {
        double[] out = new double[size];
        int start = (head - size + data.length) % data.length;
        for (int i = 0; i < size; i++) out[i] = data[(start + i) % data.length];
        return out;
    }

    public synchronized int size() { return size; }

    public int capacity() { return data.length; }

    public synchronized double last() {
        return size == 0 ? Double.NaN : data[(head - 1 + data.length) % data.length];
    }

    public synchronized void clear() { size = 0; head = 0; }
}
