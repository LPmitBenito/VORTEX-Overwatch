package dev.streetresmon.core.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Key/value settings with typed, defaulting getters, persisted as a .properties file. */
public final class CoreSettings {
    private final Properties props = new Properties();

    public synchronized String getString(String key, String def) {
        return props.getProperty(key, def);
    }

    public synchronized void set(String key, Object value) {
        props.setProperty(key, String.valueOf(value));
    }

    public synchronized int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int getIntClamped(String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, getInt(key, def)));
    }

    public synchronized double getDouble(String key, double def) {
        try {
            double d = Double.parseDouble(props.getProperty(key, "").trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? def : d;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public synchronized boolean getBoolean(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        return def;
    }

    /** A missing file is not an error (first start). */
    public synchronized void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
    }

    /** Writes to a temp file first so a crash cannot leave a half-written settings file. */
    public synchronized void save(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "StreetResmon settings");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
