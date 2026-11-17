package dev.streetresmon;

import java.nio.file.Path;

/**
 * Single place that decides where StreetResmon reads/writes files. Uses the JVM working directory (the launcher
 * normally sets it to the game directory) so it needs no loader API. If that is wrong for a setup, change only here.
 */
public final class StreetPaths {
    private StreetPaths() {}

    public static Path gameDir() {
        return Path.of("").toAbsolutePath();
    }

    public static Path dataDir() {
        return gameDir().resolve("streetresmon");
    }

    public static Path profilerDir() {
        return dataDir().resolve("profiler");
    }
}
