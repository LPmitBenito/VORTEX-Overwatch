package dev.streetresmon.core.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Incrementally reads new complete lines of a log file. Because it works with a byte offset, a line is
 * never delivered twice. Handles truncation/rotation (file shrinks or is replaced) and incomplete last lines.
 * Call from a background thread only.
 */
public final class LogTailer {
    private final Path file;
    private final int maxBytesPerPoll;
    private final long initialMaxBytes;
    private long offset = -1;
    private Object fileKey;
    private byte[] carry = new byte[0];

    public LogTailer(Path file, int maxBytesPerPoll, long initialMaxBytes) {
        this.file = file;
        this.maxBytesPerPoll = maxBytesPerPoll;
        this.initialMaxBytes = initialMaxBytes;
    }

    public List<String> poll() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(file)) {
            offset = -1;
            carry = new byte[0];
            return out;
        }
        long size = Files.size(file);
        Object key = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        boolean skipPartialFirstLine = false;
        if (offset < 0) {
            offset = Math.max(0, size - initialMaxBytes);
            skipPartialFirstLine = offset > 0;
        } else if (size < offset || (key != null && fileKey != null && !Objects.equals(key, fileKey))) {
            offset = 0;
            carry = new byte[0];
        }
        fileKey = key;
        if (size <= offset) return out;

        int toRead = (int) Math.min(size - offset, maxBytesPerPoll);
        byte[] buf = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buf);
        }
        offset += toRead;

        byte[] all = new byte[carry.length + buf.length];
        System.arraycopy(carry, 0, all, 0, carry.length);
        System.arraycopy(buf, 0, all, carry.length, buf.length);

        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                if (skipPartialFirstLine) {
                    skipPartialFirstLine = false; // first line after a mid-file jump is incomplete: drop it
                } else {
                    int end = i;
                    if (end > start && all[end - 1] == '\r') end--;
                    out.add(new String(all, start, end - start, StandardCharsets.UTF_8));
                }
                start = i + 1;
            }
        }
        carry = new byte[all.length - start];
        System.arraycopy(all, start, carry, 0, carry.length);
        if (skipPartialFirstLine) carry = new byte[0];
        return out;
    }
}
