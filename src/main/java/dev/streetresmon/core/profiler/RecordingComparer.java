package dev.streetresmon.core.profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Side-by-side comparison of two recordings. */
public final class RecordingComparer {
    public record Row(String label, double a, double b, boolean higherIsBetter) {
        public double delta() { return b - a; }
        public double deltaPercent() { return a == 0 || Double.isNaN(a) || Double.isNaN(b) ? Double.NaN : (b - a) / a * 100.0; }
        /** true = B is better, false = B is worse, null = no verdict possible */
        public Boolean bIsBetter() {
            double d = delta();
            if (Double.isNaN(d) || d == 0) return null;
            return higherIsBetter ? d > 0 : d < 0;
        }
    }

    private RecordingComparer() {}

    public static List<Row> compare(Recording ra, Recording rb) {
        Recording.Summary a = ra.summary(), b = rb.summary();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Avg FPS", a.avgFps(), b.avgFps(), true));
        rows.add(new Row("1% low FPS", a.low1PercentFps(), b.low1PercentFps(), true));
        rows.add(new Row("Max frame ms", a.maxFrameMs(), b.maxFrameMs(), false));
        rows.add(new Row("Avg client tick ms", a.avgClientTickMs(), b.avgClientTickMs(), false));
        rows.add(new Row("Max client tick ms", a.maxClientTickMs(), b.maxClientTickMs(), false));
        rows.add(new Row("Avg MSPT", a.avgMsptMs(), b.avgMsptMs(), false));
        rows.add(new Row("Avg heap MB", a.avgHeapMb(), b.avgHeapMb(), false));
        rows.add(new Row("Lag spikes", a.spikeCount(), b.spikeCount(), false));
        return rows;
    }

    public static String toText(Recording a, Recording b) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-22s %12s %12s %10s%n", "", "A:" + a.name, "B:" + b.name, "delta"));
        for (Row r : compare(a, b)) {
            sb.append(String.format(Locale.ROOT, "%-22s %12.2f %12.2f %+10.2f%n", r.label(), r.a(), r.b(), r.delta()));
        }
        return sb.toString();
    }
}
