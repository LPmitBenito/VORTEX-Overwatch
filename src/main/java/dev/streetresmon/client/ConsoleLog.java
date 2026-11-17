package dev.streetresmon.client;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe ring buffer of console lines; survives opening/closing the console screen. */
public final class ConsoleLog {
    public static final int MAX_LINES = 2000;

    /** @param tag optional chip label (may be null), @param tagColor ARGB chip background. */
    public record Line(String tag, int tagColor, String text) {}

    private static final ArrayList<Line> LINES = new ArrayList<>();
    private static boolean welcomed;

    private ConsoleLog() {}

    public static synchronized void add(String tag, int tagColor, String text) {
        LINES.add(new Line(tag, tagColor, text));
        if (LINES.size() > MAX_LINES) {
            LINES.subList(0, LINES.size() - MAX_LINES).clear();
        }
    }

    public static void info(String text) { add("streetresmon", 0xFF2E7DAF, text); }

    public static void warn(String text) { add("streetresmon", 0xFFB9892A, "^3" + text); }

    public static void error(String text) { add("streetresmon", 0xFFB03A3A, "^1" + text); }

    public static void echo(String text) { add(null, 0, "^5> ^7" + text); }

    public static synchronized void clear() { LINES.clear(); }

    public static synchronized int size() { return LINES.size(); }

    public static synchronized List<Line> slice(int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(LINES.size(), to);
        return f >= t ? List.of() : new ArrayList<>(LINES.subList(f, t));
    }

    static synchronized void welcomeOnce() {
        if (welcomed) return;
        welcomed = true;
        info("StreetResmon console ready. Type ^2help^7 for commands, ^2resmon 1^7 for the resource monitor.");
        info("F8 / Esc closes the console.");
    }
}
