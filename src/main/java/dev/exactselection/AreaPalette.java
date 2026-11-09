package dev.exactselection;

import java.util.Locale;

public final class AreaPalette {
    public static final int[] COLORS = {
        0xFF5555, 0xFF8A3D, 0xFFCF62, 0xF2E85C, 0x9BE15D, 0x7CE38B, 0x3FC77A, 0x59E3CE,
        0x4FD1F5, 0x63A8FF, 0x4466FF, 0x7E6BFF, 0xB78BFF, 0xE36BFF, 0xFF6BB5, 0xFF6B6B,
        0x8B5A2B, 0xC9A27A, 0x2E7D32, 0x1E5A8C, 0x6A3D9A, 0x9E9E9E, 0x404040, 0xFFFFFF
    };
    public static final int COLUMNS = 8;
    public static final int DEFAULT = 0x59E3CE;

    private AreaPalette() {}

    public static int parse(String text) {
        String hex = text == null ? "" : text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 3)
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        if (hex.length() != 6 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) return -1;
        return Integer.parseInt(hex, 16);
    }

    public static String format(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /** RGB from hue, saturation and brightness, each 0..1. */
    public static int hsv(double h, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs(h * 6 % 2 - 1)), m = v - c;
        int sector = (int) (h * 6) % 6;
        double r = sector == 0 || sector == 5 ? c : sector == 1 || sector == 4 ? x : 0;
        double g = sector == 1 || sector == 2 ? c : sector == 0 || sector == 3 ? x : 0;
        double b = sector == 3 || sector == 4 ? c : sector == 2 || sector == 5 ? x : 0;
        return (int) Math.round((r + m) * 255) << 16
                | (int) Math.round((g + m) * 255) << 8
                | (int) Math.round((b + m) * 255);
    }

    /** Hue, saturation and brightness (each 0..1) of an RGB color; grey has hue 0. */
    public static double[] toHsv(int color) {
        double r = (color >> 16 & 255) / 255.0, g = (color >> 8 & 255) / 255.0, b = (color & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b)), d = max - Math.min(r, Math.min(g, b));
        double h = d == 0 ? 0 : max == r ? ((g - b) / d % 6 + 6) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new double[] {h / 6, max == 0 ? 0 : d / max, max};
    }
}
