package dev.exactselection;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.List;

public final class AreaExportCheck {
    private static BufferedImage compose(BufferedImage raw, int x, int z, AreaGeometry geometry,
                                         List<SavedAreaStore.Area> areas, List<SavedAreaStore.Group> groups) throws Exception {
        Method method = AreaExportService.class.getDeclaredMethod("compose", BufferedImage.class, int.class, int.class,
                int.class, int.class, AreaGeometry.class, List.class, List.class);
        method.setAccessible(true);
        return (BufferedImage) method.invoke(null, raw, x, z, (int) geometry.bounds().width(),
                (int) geometry.bounds().length(), geometry, areas, groups);
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
        BufferedImage result = compose(raw, 0, 0, territory, areas, List.of());
        int white = 0, darkPixels = 0;
        for (int z = 33; z < 60; z++) for (int x = 34; x < 105; x++) {
            int rgb = result.getRGB(x, z);
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            if (r > 235 && g > 235 && b > 235) white++;
            if (r < 50 && g < 50 && b < 50) darkPixels++;
        }
        require(white > 50, "Area name does not have a bright, readable foreground");
        require(darkPixels > 100, "Label background/outline is missing or was covered by a later area fill");
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
        System.out.println("PASS: bright bold area labels, dark backing, overlapping fills, and polygon clipping");
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
        BufferedImage preview = checkLabels();
        if (args.length == 1) javax.imageio.ImageIO.write(preview, "png", java.nio.file.Path.of(args[0]).toFile());
    }
}
