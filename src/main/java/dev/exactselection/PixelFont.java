package dev.exactselection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Minecraft's bitmap fonts (ASCII, accented, Cyrillic/Greek) read from the game's font definitions, so exported maps
 * use the same lettering as the in-game map. Immutable once loaded; safe to draw with off the client thread.
 */
final class PixelFont {
    /** One glyph: pixels relative to the top of the 9-pixel line, and the advance to the next character. */
    record Glyph(int advance, int top, boolean[][] pixels) {}

    /** Opens a resource of the minecraft namespace, e.g. "font/default.json" or "textures/font/ascii.png". */
    @FunctionalInterface interface Resources { InputStream open(String path) throws IOException; }

    static final int LINE_HEIGHT = 9;
    private final Map<Integer, Glyph> glyphs;

    PixelFont(Map<Integer, Glyph> glyphs) { this.glyphs = Map.copyOf(glyphs); }

    /** Follows "reference" providers from font/default.json and reads every "bitmap" and "space" provider. */
    static PixelFont load(Resources resources) throws IOException {
        Map<Integer, Glyph> glyphs = new HashMap<>();
        read(resources, "minecraft:default", glyphs, new HashSet<>());
        if (!glyphs.containsKey((int) 'A')) throw new IOException("Minecraft font has no Latin glyphs");
        return new PixelFont(glyphs);
    }

    private static void read(Resources resources, String id, Map<Integer, Glyph> glyphs, Set<String> seen) throws IOException {
        if (!seen.add(id)) return;
        JsonObject json;
        try (InputStream in = resources.open("font/" + path(id) + ".json")) {
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (JsonElement element : json.getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            switch (provider.get("type").getAsString()) {
                case "reference" -> read(resources, provider.get("id").getAsString(), glyphs, seen);
                case "space" -> {
                    for (var entry : provider.getAsJsonObject("advances").entrySet())
                        glyphs.putIfAbsent(entry.getKey().codePointAt(0),
                                new Glyph(Math.round(entry.getValue().getAsFloat()), 0, new boolean[0][0]));
                }
                case "bitmap" -> bitmap(resources, provider, glyphs);
                default -> { /* unifont and TTF fallbacks are not reproduced; AWT covers rare characters */ }
            }
        }
    }

    private static void bitmap(Resources resources, JsonObject provider, Map<Integer, Glyph> glyphs) throws IOException {
        BufferedImage image;
        try (InputStream in = resources.open("textures/" + path(provider.get("file").getAsString()))) { image = ImageIO.read(in); }
        if (image == null) return;
        var rows = provider.getAsJsonArray("chars");
        int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
        int cellW = image.getWidth() / columns, cellH = image.getHeight() / rows.size();
        int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
        int ascent = provider.get("ascent").getAsInt();
        double scale = (double) height / cellH;
        for (int row = 0; row < rows.size(); row++) {
            int[] codes = rows.get(row).getAsString().codePoints().toArray();
            for (int col = 0; col < codes.length; col++) {
                if (codes[col] == 0 || glyphs.containsKey(codes[col])) continue;
                int x0 = col * cellW, y0 = row * cellH, used = 0;
                for (int x = cellW - 1; x >= 0 && used == 0; x--)
                    for (int y = 0; y < cellH; y++) if ((image.getRGB(x0 + x, y0 + y) >>> 24) != 0) { used = x + 1; break; }
                int width = (int) Math.round(used * scale);
                boolean[][] pixels = new boolean[height][width];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                    pixels[y][x] = (image.getRGB(x0 + Math.min(cellW - 1, (int) (x / scale)), y0 + Math.min(cellH - 1, (int) (y / scale))) >>> 24) > 0;
                // Same as Minecraft: the glyph's top sits ascent rows above the 7-pixel baseline, advance adds a gap.
                glyphs.put(codes[col], new Glyph(width + 1, 7 - ascent, pixels));
            }
        }
    }

    private static String path(String id) { return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id; }

    boolean supports(String text) { return text.codePoints().allMatch(glyphs::containsKey); }

    /** Width in font pixels; the last glyph's trailing gap is not counted. */
    int width(String text) {
        int width = 0;
        for (int code : text.codePoints().toArray()) width += glyphs.get(code).advance();
        return Math.max(0, width - 1);
    }

    /** Draws text with Minecraft's drop shadow at {@code scale} image pixels per font pixel. */
    void draw(Graphics2D graphics, String text, int x, int y, int scale, int argb) {
        int shadow = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
        drawRun(graphics, text, x + scale, y + scale, scale, shadow);
        drawRun(graphics, text, x, y, scale, argb);
    }

    private void drawRun(Graphics2D graphics, String text, int x, int y, int scale, int argb) {
        graphics.setColor(new Color(argb, true));
        int cursor = x;
        for (int code : text.codePoints().toArray()) {
            Glyph glyph = glyphs.get(code);
            for (int row = 0; row < glyph.pixels().length; row++) for (int col = 0; col < glyph.pixels()[row].length; col++)
                if (glyph.pixels()[row][col]) graphics.fillRect(cursor + col * scale, y + (glyph.top() + row) * scale, scale, scale);
            cursor += glyph.advance() * scale;
        }
    }
}
