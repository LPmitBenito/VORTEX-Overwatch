package dev.streetresmon.core;

import java.util.Locale;

/** A value together with its quality and a short note about where it came from. */
public record Metric(double value, Quality quality, String unit, String note) {
    public static Metric measured(double value, String unit) {
        return new Metric(value, Quality.MEASURED, unit, "");
    }

    public static Metric estimated(double value, String unit, String note) {
        return new Metric(value, Quality.ESTIMATED, unit, note);
    }

    public static Metric unavailable(String reason) {
        return new Metric(Double.NaN, Quality.UNAVAILABLE, "", reason);
    }

    public boolean available() {
        return quality != Quality.UNAVAILABLE && !Double.isNaN(value);
    }

    public String format() {
        if (!available()) return "n/a";
        String num = String.format(Locale.ROOT, "%.2f", value);
        String s = unit == null || unit.isEmpty() ? num : num + " " + unit;
        return quality == Quality.ESTIMATED ? "~" + s : s;
    }
}
