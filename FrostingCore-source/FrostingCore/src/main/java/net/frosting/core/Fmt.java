package net.frosting.core;

import java.util.Locale;

/** Number / time formatting and parsing (1k, 2.5m, 3b ...). */
public final class Fmt {
    private Fmt() {
    }

    public static String money(double v) {
        return "$" + shortNum(v);
    }

    public static String shortNum(double v) {
        double a = Math.abs(v);
        double d = v;
        String suffix = "";
        if (a >= 1e12) {
            d = v / 1e12;
            suffix = "T";
        } else if (a >= 1e9) {
            d = v / 1e9;
            suffix = "B";
        } else if (a >= 1e6) {
            d = v / 1e6;
            suffix = "M";
        } else if (a >= 1e3) {
            d = v / 1e3;
            suffix = "K";
        }
        String s = String.format(Locale.US, "%.2f", d);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s + suffix;
    }

    /** Parses 100, 1.5k, 2m, 3b, 4t ($ and commas allowed). Returns NaN when invalid. */
    public static double parse(String raw) {
        if (raw == null) return Double.NaN;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) return Double.NaN;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1e3;
        else if (last == 'm') mult = 1e6;
        else if (last == 'b') mult = 1e9;
        else if (last == 't') mult = 1e12;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            return Double.isFinite(v) ? v : Double.NaN;
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    /** 2d 4h, 3h 20m, 5m 10s ... */
    public static String duration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
