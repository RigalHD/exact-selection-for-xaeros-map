package dev.exactselection;

import java.util.Locale;

public final class AreaPalette {
    public static final int[] COLORS = {
            0xFF5555, 0xFF8A3D, 0xFFCF62, 0xF2E85C, 0x9BE15D, 0x7CE38B, 0x3FC77A, 0x59E3CE,
            0x4FD1F5, 0x63A8FF, 0x4466FF, 0x7E6BFF, 0xB78BFF, 0xE36BFF, 0xFF6BB5, 0xFF6B6B,
            0x8B5A2B, 0xC9A27A, 0x2E7D32, 0x1E5A8C, 0x6A3D9A, 0x9E9E9E, 0x404040, 0xFFFFFF};
    public static final int COLUMNS = 8;
    public static final int DEFAULT = 0x59E3CE;

    private AreaPalette() {}

    public static int parse(String text) {
        String hex = text == null ? "" : text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        if (hex.length() != 6 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) return -1;
        return Integer.parseInt(hex, 16);
    }

    public static String format(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }
}
