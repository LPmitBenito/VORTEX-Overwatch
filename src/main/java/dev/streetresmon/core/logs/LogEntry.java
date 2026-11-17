package dev.streetresmon.core.logs;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** One log record; continuation lines (stack traces) are attached to {@link #details()}. */
public final class LogEntry {
    public static final int MAX_DETAILS = 200;

    public final long seq;
    public final String time, thread, source, message;
    public final LogLevel level;
    private final List<String> details = new CopyOnWriteArrayList<>();

    public LogEntry(long seq, String time, String thread, LogLevel level, String source, String message) {
        this.seq = seq; this.time = time; this.thread = thread; this.level = level; this.source = source; this.message = message;
    }

    public void addDetail(String line) { if (details.size() < MAX_DETAILS) details.add(line); }

    public List<String> details() { return details; }

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(time).append("] [").append(thread).append('/').append(level).append("] [").append(source).append("]: ").append(message);
        for (String d : details) sb.append('\n').append(d);
        return sb.toString();
    }
}
