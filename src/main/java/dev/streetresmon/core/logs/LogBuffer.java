package dev.streetresmon.core.logs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Bounded, thread-safe store of log entries with filtering, search and export. */
public final class LogBuffer {
    private final int capacity;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long dropped;

    public LogBuffer(int capacity) { this.capacity = capacity; }

    public synchronized void add(LogEntry e) {
        entries.addLast(e);
        while (entries.size() > capacity) { entries.removeFirst(); dropped++; }
    }

    public synchronized int size() { return entries.size(); }

    public synchronized long dropped() { return dropped; }

    public synchronized void clear() { entries.clear(); }

    public synchronized List<LogEntry> snapshot() { return new ArrayList<>(entries); }

    /** @param minLevel entries below this level are hidden; @param query case-insensitive substring (null/empty = all) */
    public synchronized List<LogEntry> filter(LogLevel minLevel, String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : entries) {
            if (e.level.ordinal() < minLevel.ordinal()) continue;
            if (!q.isEmpty() && !e.text().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(e);
        }
        return out;
    }

    public synchronized List<LogEntry> lastProblems(int n) {
        List<LogEntry> all = new ArrayList<>();
        for (LogEntry e : entries) if (e.level.ordinal() >= LogLevel.WARNING.ordinal()) all.add(e);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    public static String exportText(List<LogEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : list) sb.append(e.text()).append('\n');
        return sb.toString();
    }
}
