package dev.exactselection;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.List;

public final class AreaExportCheck {
    private static BufferedImage compose(BufferedImage raw, int x, int z, AreaGeometry geometry,
                                         List<SavedAreaStore.Area> areas, List<SavedAreaStore.Group> groups) throws Exception {
        Method method = AreaExportService.class.getDeclaredMethod("compose", BufferedImage.class, int.class, int.class,
                int.class, int.class, AreaGeometry.class, List.class, List.class, int.class, boolean.class, PixelFont.class);
        method.setAccessible(true);
        return (BufferedImage) method.invoke(null, raw, x, z, (int) geometry.bounds().width(),
                (int) geometry.bounds().length(), geometry, areas, groups, 1, true, null);
    }

    private static BufferedImage scaled(BufferedImage raw, AreaGeometry geometry, List<SavedAreaStore.Area> areas,
                                        PixelFont font, int scale) throws Exception {
        Method method = AreaExportService.class.getDeclaredMethod("compose", BufferedImage.class, int.class, int.class,
                int.class, int.class, AreaGeometry.class, List.class, List.class, int.class, boolean.class, PixelFont.class);
        method.setAccessible(true);
        return (BufferedImage) method.invoke(null, raw, 0, 0, (int) geometry.bounds().width(),
                (int) geometry.bounds().length(), geometry, areas, List.of(), scale, true, font);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static BufferedImage checkLabels() throws Exception {
        BufferedImage raw = new BufferedImage(512, 220, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < raw.getHeight(); z++) for (int x = 0; x < raw.getWidth(); x++)
            raw.setRGB(x, z, x < 256 ? 0xFF4D712D : 0xFF18241A);
        var territory = new RectangleGeometry(new SelectionBounds(0, 0, 511, 219));
        var first = SavedAreaStore.create("Магаз2", new SelectionBounds(30, 30, 230, 100), 0xFFE268, 25, null);
        var last = SavedAreaStore.create("Перекрывающая область", new SelectionBounds(0, 0, 511, 219), 0xFFFFFF, 40, null);
        var red = SavedAreaStore.create("Красная зона", new SelectionBounds(30, 130, 230, 200), 0xFF5573, 80, null);
        var dark = SavedAreaStore.create("Тёмная зона", new SelectionBounds(280, 30, 480, 100), 0x000000, 80, null);
        var clear = SavedAreaStore.create("Без заливки", new SelectionBounds(280, 130, 480, 200), 0x00D6C3, 0, null);
        var areas = List.of(first, red, dark, clear, last);
        // Pixel-font labels at 2 image pixels per block, as the export window does by default.
        java.util.Map<Integer, PixelFont.Glyph> glyphs = new java.util.HashMap<>();
        boolean[][] block = new boolean[7][5];
        for (boolean[] row : block) java.util.Arrays.fill(row, true);
        for (var area : areas) area.name().codePoints().forEach(code -> glyphs.put(code, new PixelFont.Glyph(6, 0, block)));
        glyphs.put((int) ' ', new PixelFont.Glyph(4, 0, new boolean[0][0]));
        BufferedImage result = scaled(raw, territory, areas, new PixelFont(glyphs), 2);
        require(result.getWidth() == 1024 && result.getHeight() == 440, "Scale did not enlarge the image");
        require(result.getRGB(300, 300) == result.getRGB(301, 301) && result.getRGB(300, 300) == result.getRGB(300, 301),
                "Map pixels were smoothed instead of enlarged as blocks");
        int text = 0, shadow = 0, backing = 0;
        for (int y = 60; y < 110; y++) for (int x = 60; x < 460; x++) {
            int rgb = result.getRGB(x, y) & 0xFFFFFF;
            if (rgb == 0xFFE268) text++;
            if (rgb == (0xFFE268 & 0xFCFCFC) >> 2) shadow++;
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            if (r < 90 && g < 90 && b < 90) backing++;
        }
        require(text > 50, "Area name is not drawn in the area's own color above later fills");
        require(shadow > 20, "Area name lacks Minecraft's drop shadow");
        require(backing > 100, "Label backing is missing");
        int lifted = 0;
        for (int y = 60; y < 110; y++) for (int x = 560; x < 960; x++) if ((result.getRGB(x, y) & 0xFFFFFF) == 0x7F7F7F) lifted++;
        require(lifted > 20, "A black area's name is not lifted to stay readable");
        var triangle = new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(512, 0), new GridPoint(0, 220)));
        BufferedImage clipped = compose(raw, 0, 0, triangle, areas, List.of());
        require(clipped.getRGB(500, 210) == 0, "Area labels paint outside the exported polygon");

        var polygonTerritory = new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(256, 0), new GridPoint(0, 256)));
        var disjoint = SavedAreaStore.create("Must not appear", new PolygonGeometry(List.of(
                new GridPoint(256, 256), new GridPoint(256, 60), new GridPoint(60, 256))), 0xFF5573, 100, null);
        BufferedImage withoutDisjoint = compose(raw, 0, 0, polygonTerritory, List.of(), List.of());
        BufferedImage withDisjoint = compose(raw, 0, 0, polygonTerritory, List.of(disjoint), List.of());
        int unexpected = 0;
        for (int y = 0; y < withDisjoint.getHeight(); y++) for (int x = 0; x < withDisjoint.getWidth(); x++)
            if (withDisjoint.getRGB(x, y) != withoutDisjoint.getRGB(x, y)) unexpected++;
        require(unexpected == 0, "A disjoint overlay label leaked into the exported territory");
        System.out.println("PASS: enlarged crisp map, pixel-font labels in area color with shadow and backing, polygon clipping");
        return result;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        BufferedImage raw = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) raw.setRGB(x, z, 0xFF000000 | x << 8 | z);
        var rectangle = new RectangleGeometry(new SelectionBounds(-13, -11, 2, 4));
        BufferedImage cropped = compose(raw, 3, 5, rectangle, List.of(), List.of());
        require(cropped.getWidth() == 16 && cropped.getHeight() == 16, "Inclusive rectangle has incorrect export dimensions");
        require(cropped.getRGB(0, 0) == raw.getRGB(3, 5)
                && cropped.getRGB(15, 15) == raw.getRGB(18, 20), "Negative-coordinate chunk crop shifts map pixels");
        var polygon = new PolygonGeometry(List.of(new GridPoint(-13, -11), new GridPoint(3, -11), new GridPoint(-13, 5)));
        BufferedImage masked = compose(raw, 3, 5, polygon, List.of(), List.of());
        require(masked.getRGB(2, 2) == raw.getRGB(5, 7), "Polygon interior lost map pixels");
        require(masked.getRGB(14, 14) == 0, "Pixels outside the polygon are not transparent");
        var visible = SavedAreaStore.create("Visible", new SelectionBounds(-12, -10, -6, -4), 0xFF0000, 100, null);
        var hidden = new SavedAreaStore.Area("hidden", "Hidden", -3, -1, 2, 4, 0x00FF00, 100, null, false);
        var group = new SavedAreaStore.Group("off", "Hidden group", false);
        var grouped = SavedAreaStore.create("Grouped", new SelectionBounds(-5, -9, 1, -3), 0x0000FF, 100, group.id());
        var transparent = SavedAreaStore.create("No fill", new SelectionBounds(-12, -1, -6, 4), 0xFFFFFF, 0, null);
        var areas = List.of(visible, hidden, grouped, transparent);
        BufferedImage output = compose(raw, 3, 5, rectangle, areas, List.of(group));
        require(output.getRGB(4, 4) == 0xFFFF0000, "Visible area fill is missing from export");
        require(output.getRGB(12, 12) == cropped.getRGB(12, 12), "Hidden area leaked into export");
        require(output.getRGB(11, 5) == cropped.getRGB(11, 5), "Hidden group leaked into export");
        require(output.getRGB(4, 13) == cropped.getRGB(4, 13), "Zero opacity area filled the map");
        var half = SavedAreaStore.create("Half", new SelectionBounds(-12, -10, -6, -4), 0xFF0000, 50, null);
        BufferedImage blended = compose(raw, 3, 5, rectangle, List.of(half), List.of());
        int red = blended.getRGB(4, 4) >> 16 & 255;
        require(red >= 127 && red <= 128, "Area opacity is not respected in export");
        BufferedImage clipped = compose(raw, 3, 5, polygon,
                List.of(SavedAreaStore.create("Cover", new SelectionBounds(-13, -11, 2, 4), 0xFFFFFF, 100, null)), List.of());
        require(clipped.getRGB(14, 14) == 0, "Overlay painted outside the selected polygon");
        System.out.println("PASS: exact PNG crop, polygon transparency, visible-area overlays, groups, and opacity");
        // The real Minecraft font files from the game JAR on the classpath.
        PixelFont minecraft = PixelFont.load(path -> {
            var in = AreaExportCheck.class.getResourceAsStream("/assets/minecraft/" + path);
            if (in == null) throw new java.io.FileNotFoundException(path);
            return in;
        });
        require(minecraft.supports("Главное здание Dragon_mm 0709") && minecraft.width("A") == 5 && minecraft.width("i") == 1
                && minecraft.width(" ") == 3, "Minecraft's bitmap font was not read correctly");
        System.out.println("PASS: Minecraft bitmap font with Latin and Cyrillic glyphs and vanilla widths");
        BufferedImage preview = checkLabels();
        if (args.length == 1) javax.imageio.ImageIO.write(preview, "png", java.nio.file.Path.of(args[0]).toFile());
    }
}
