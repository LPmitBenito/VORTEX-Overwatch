package dev.streetresmon.core.profiler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Plain-text recording format (tab separated). Call from a background thread, never from the render thread. */
public final class RecordingIO {
    private static final String HEADER = "STREETRESMON-REC 1";

    private RecordingIO() {}

    public static String sanitizeFileName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "recording" : s;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    private static String num(double d) { return Double.isNaN(d) ? "NaN" : Double.toString(d); }

    public static void save(Recording r, Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(HEADER); w.newLine();
            w.write("name\t" + esc(r.name)); w.newLine();
            w.write("started\t" + r.startedAtMs); w.newLine();
            w.write("ended\t" + r.endedAtMs); w.newLine();
            w.write("truncated\t" + r.truncated); w.newLine();
            for (FrameSample s : r.samples) {
                w.write(String.join("\t", "S", Long.toString(s.timeMs()), num(s.fps()), num(s.frameMs()), num(s.clientTickMs()),
                        num(s.serverMspt()), num(s.heapUsedMb()), num(s.cpuLoad()), Long.toString(s.gcCount()), Long.toString(s.gcTimeMs())));
                w.newLine();
            }
            for (Spike p : r.spikes) {
                w.write(String.join("\t", "P", Long.toString(p.timeMs()), p.kind(), num(p.valueMs()), num(p.baselineMs()), esc(p.hint())));
                w.newLine();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Recording load(Path file) throws IOException {
        try (BufferedReader rd = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = rd.readLine();
            if (!HEADER.equals(line)) throw new IOException("Not a StreetResmon recording: " + file);
            String name = "";
            long started = 0, ended = 0;
            boolean truncated = false;
            List<FrameSample> samples = new ArrayList<>();
            List<Spike> spikes = new ArrayList<>();
            int ln = 1;
            try {
                while ((line = rd.readLine()) != null) {
                    ln++;
                    if (line.isEmpty()) continue;
                    String[] f = line.split("\t", -1);
                    switch (f[0]) {
                        case "name" -> name = unesc(f[1]);
                        case "started" -> started = Long.parseLong(f[1]);
                        case "ended" -> ended = Long.parseLong(f[1]);
                        case "truncated" -> truncated = Boolean.parseBoolean(f[1]);
                        case "S" -> samples.add(new FrameSample(Long.parseLong(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                                Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
                                Long.parseLong(f[8]), Long.parseLong(f[9])));
                        case "P" -> spikes.add(new Spike(Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4]), unesc(f[5])));
                        default -> { /* unknown line: ignore for forward compatibility */ }
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("Corrupt recording at line " + ln + ": " + e.getMessage(), e);
            }
            return new Recording(name, started, ended, truncated, samples, spikes);
        }
    }

    public static String formatNumber(double d) { return String.format(Locale.ROOT, "%.2f", d); }
}
