package dev.streetresmon.core.logs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw lines of latest.log into entries. A line matching the header pattern starts a new entry; every
 * other line (stack trace, multi-line message) is attached to the previous entry.
 * Header format handled: [time] [thread/LEVEL] [source]: message   (the [source] part is optional)
 */
public final class LogAssembler {
    private static final Pattern HEADER = Pattern.compile("^\\[([^\\]]+)\\] \\[([^/\\]]+)/([A-Za-z]+)\\](?: \\[([^\\]]*)\\])?: ?(.*)$");

    private long seq;
    private LogEntry current;

    /** @return the new entry if this line started one, otherwise null */
    public LogEntry feed(String line) {
        Matcher m = HEADER.matcher(line);
        if (m.matches()) {
            current = new LogEntry(++seq, m.group(1), m.group(2), level(m.group(3)),
                    m.group(4) == null ? "" : m.group(4), m.group(5));
            return current;
        }
        if (current != null) {
            current.addDetail(line);
            return null;
        }
        current = new LogEntry(++seq, "", "", LogLevel.INFO, "", line);
        return current;
    }

    static LogLevel level(String s) {
        switch (s.toUpperCase(java.util.Locale.ROOT)) {
            case "WARN": case "WARNING": return LogLevel.WARNING;
            case "ERROR": case "FATAL": return LogLevel.ERROR;
            case "DEBUG": case "TRACE": return LogLevel.DEBUG;
            default: return LogLevel.INFO;
        }
    }
}
