package dev.streetresmon.core.input;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Tracks in-game input state for the Input Viewer. Fed by the game's own input events only. */
public final class InputTracker {
    public record Snapshot(List<String> keys, String combo, boolean left, boolean right, boolean middle,
                           int scrollDirection, double mouseSpeedPxPerSec, int leftCps, int rightCps) {}

    private static final long SCROLL_VISIBLE_MS = 300;
    private static final long WINDOW_MS = 1000;

    private final Map<Integer, String> pressed = new LinkedHashMap<>();
    private boolean left, right, middle;
    private int lastScrollDir;
    private long lastScrollAt = Long.MIN_VALUE / 2;
    private final Deque<long[]> moves = new ArrayDeque<>(); // {timeMs, distance*1000}
    private final Deque<Long> leftClicks = new ArrayDeque<>(), rightClicks = new ArrayDeque<>();

    public synchronized void keyDown(int code, String name) { pressed.putIfAbsent(code, name); }

    public synchronized void keyUp(int code) { pressed.remove(code); }

    /** @param button 0 = left, 1 = right, 2 = middle */
    public synchronized void mouse(int button, boolean down, long nowMs) {
        switch (button) {
            case 0 -> { left = down; if (down) leftClicks.addLast(nowMs); }
            case 1 -> { right = down; if (down) rightClicks.addLast(nowMs); }
            case 2 -> middle = down;
            default -> { }
        }
    }

    public synchronized void scroll(double delta, long nowMs) {
        if (delta == 0) return;
        lastScrollDir = delta > 0 ? 1 : -1;
        lastScrollAt = nowMs;
    }

    public synchronized void mouseMove(double dx, double dy, long nowMs) {
        moves.addLast(new long[] {nowMs, (long) (Math.sqrt(dx * dx + dy * dy) * 1000)});
    }

    /** Releases everything, e.g. when the window loses focus or a screen opens (avoids "stuck" keys). */
    public synchronized void releaseAll() {
        pressed.clear();
        left = right = middle = false;
    }

    private static int rank(String n) {
        switch (n) {
            case "Ctrl": return 0;
            case "Shift": return 1;
            case "Alt": return 2;
            default: return 3;
        }
    }

    public synchronized Snapshot snapshot(long nowMs) {
        while (!moves.isEmpty() && nowMs - moves.peekFirst()[0] > WINDOW_MS) moves.removeFirst();
        while (!leftClicks.isEmpty() && nowMs - leftClicks.peekFirst() > WINDOW_MS) leftClicks.removeFirst();
        while (!rightClicks.isEmpty() && nowMs - rightClicks.peekFirst() > WINDOW_MS) rightClicks.removeFirst();
        double dist = 0;
        for (long[] m : moves) dist += m[1] / 1000.0;
        List<String> keys = new ArrayList<>(pressed.values());
        keys.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        StringJoiner j = new StringJoiner("+");
        for (String k : keys) j.add(k);
        int scroll = nowMs - lastScrollAt <= SCROLL_VISIBLE_MS ? lastScrollDir : 0;
        return new Snapshot(keys, j.toString(), left, right, middle, scroll, dist * 1000.0 / WINDOW_MS,
                leftClicks.size(), rightClicks.size());
    }
}
