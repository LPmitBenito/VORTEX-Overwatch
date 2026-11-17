package dev.streetresmon.core.resmon;

/**
 * Background sampler for one target thread (e.g. render or server thread). Each sample costs the target
 * a short safepoint, so keep the interval >= 10 ms (default 25 ms). Samples where the thread is not RUNNABLE
 * or sits in a native method count as idle. Known limit: native time (e.g. GPU driver) is not attributed.
 */
public final class StackSampler implements AutoCloseable {
    private final Thread target;
    private final ModSampleTable table;
    private final long intervalMs;
    private volatile boolean running;
    private Thread worker;

    public StackSampler(Thread target, ModSampleTable table, long intervalMs) {
        if (intervalMs < 1) throw new IllegalArgumentException("intervalMs must be >= 1");
        this.target = target;
        this.table = table;
        this.intervalMs = intervalMs;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "StreetResmon-Sampler");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (!target.isAlive()) continue;
                if (target.getState() != Thread.State.RUNNABLE) {
                    table.recordIdle();
                    continue;
                }
                table.record(target.getStackTrace());
            } catch (RuntimeException ignored) {
                // never let the sampler die or disturb the game
            }
        }
    }

    public boolean isRunning() { return running; }

    @Override
    public synchronized void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }
}
