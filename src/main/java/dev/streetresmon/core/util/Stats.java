package dev.streetresmon.core.util;

import java.util.Arrays;

/** Small statistics helpers. All of them ignore NaN values and return NaN when nothing is left. */
public final class Stats {
    private Stats() {}

    private static double[] clean(double[] v) {
        int n = 0;
        for (double d : v) if (!Double.isNaN(d)) n++;
        if (n == v.length) return v;
        double[] o = new double[n];
        int i = 0;
        for (double d : v) if (!Double.isNaN(d)) o[i++] = d;
        return o;
    }

    public static double avg(double[] v) {
        v = clean(v);
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (double d : v) s += d;
        return s / v.length;
    }

    public static double max(double[] v) {
        v = clean(v);
        if (v.length == 0) return Double.NaN;
        double m = v[0];
        for (double d : v) m = Math.max(m, d);
        return m;
    }

    public static double min(double[] v) {
        v = clean(v);
        if (v.length == 0) return Double.NaN;
        double m = v[0];
        for (double d : v) m = Math.min(m, d);
        return m;
    }

    public static double median(double[] v) {
        v = clean(v).clone();
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
    }

    /** Average of the lowest {@code fraction} (0..1] of the values, at least one value. Used for "1% low FPS". */
    public static double lowFractionAvg(double[] v, double fraction) {
        v = clean(v).clone();
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        int n = Math.max(1, (int) Math.ceil(v.length * fraction));
        double s = 0;
        for (int i = 0; i < n; i++) s += v[i];
        return s / n;
    }
}
