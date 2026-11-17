package dev.streetresmon.perf;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One low-priority daemon thread for file I/O and analysis, so nothing blocks the render thread. */
public final class Background {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StreetResmon-IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private Background() {}

    public static void run(Runnable r) {
        EXEC.execute(r);
    }
}
