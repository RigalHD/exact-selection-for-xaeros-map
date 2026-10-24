package dev.exactselection;

import dev.exactselection.mixin.MapSaveLoadAccessor;
import dev.exactselection.mixin.PNGExporterAccessor;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import xaero.map.MapProcessor;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.export.PNGExportResult;
import xaero.map.gui.MapTileSelection;
import xaero.map.world.MapWorld;

public final class AreaExportService {
    public record Result(Path file, int width, int height) {}
    /** The area exceeds {@link #MAX_PIXELS}; the user can act on this, unlike other export failures. */
    public static final class TooLargeException extends IllegalArgumentException {
        TooLargeException(String message) { super(message); }
    }
    private record VisibleOverlay(SavedAreaStore.Area area, java.awt.geom.Area visibleShape) {}
    private record LabelLayout(float x, float y, java.awt.Rectangle bounds) {}
    public static final long MAX_PIXELS = 8_000_000L;

    private AreaExportService() {}

    public static Result export(MapProcessor processor, MapBridge.Context context, AreaGeometry territory,
                                List<SavedAreaStore.Area> overlays, List<SavedAreaStore.Group> groups) throws Exception {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Map export must run on the client thread");
        if (processor == null || context == null || territory == null) throw new IllegalArgumentException("Missing map or area context");
        MapWorld world = processor.getMapWorld();
        if (world == null || world != context.world() || !processor.isMapWorldUsable()
                || world.getCurrentDimension() == null
                || !Objects.equals(world.getCurrentDimensionId(), context.dimension())
                || !Objects.equals(world.getCurrentDimension().getCurrentMultiworld(), context.subworld()))
            throw new IllegalStateException("The viewed map context changed");

        GeometryBounds bounds = territory.bounds();
        long width = bounds.width(), height = bounds.length();
        long pixels = Math.multiplyExact(width, height);
        if (width < 1 || height < 1 || pixels > MAX_PIXELS)
            throw new TooLargeException("Area is too large to export safely");
        long minChunkX = Math.floorDiv(bounds.minX(), 16L);
        long minChunkZ = Math.floorDiv(bounds.minZ(), 16L);
        long maxChunkX = Math.floorDiv(bounds.maxX() - 1, 16L);
        long maxChunkZ = Math.floorDiv(bounds.maxZ() - 1, 16L);
        long rawWidth = Math.multiplyExact(maxChunkX - minChunkX + 1, 16L);
        long rawHeight = Math.multiplyExact(maxChunkZ - minChunkZ + 1, 16L);
        if (Math.multiplyExact(rawWidth, rawHeight) > MAX_PIXELS)
            throw new TooLargeException("Chunk-aligned export would exceed the safe image size");

        MapSaveLoad saveLoad = processor.getMapSaveLoad();
        MapSaveLoadAccessor saveLoadAccess = (MapSaveLoadAccessor) saveLoad;
        if (saveLoadAccess.exactselection$isExporting())
            throw new IllegalStateException("Xaero is already exporting a map");
        var mapExporter = saveLoadAccess.exactselection$getPngExporter();
        PNGExporterAccessor exporterAccess = (PNGExporterAccessor) mapExporter;
        Path previousDestination = exporterAccess.exactselection$getDestinationPath();
        Path exportRoot = Objects.requireNonNull(previousDestination, "Xaero export directory is not ready")
                .toAbsolutePath().normalize();
        Files.createDirectories(exportRoot);
        String id = UUID.randomUUID().toString();
        Path jobDirectory = exportRoot.resolve(".raw-" + id);
        Files.createDirectories(jobDirectory);
        Path finalPath = exportRoot.resolve("ExactSelection-" + id + ".png");
        Path tempFinal = exportRoot.resolve(".ExactSelection-" + id + ".tmp");
        saveLoadAccess.exactselection$setExporting(true);
        try {
            exporterAccess.exactselection$setDestinationPath(jobDirectory);
            MapTileSelection selection = new MapTileSelection(Math.toIntExact(minChunkX), Math.toIntExact(minChunkZ));
            selection.setEnd(Math.toIntExact(maxChunkX), Math.toIntExact(maxChunkZ));
            PNGExportResult nativeResult;
            try (AreaExportConfigOverride.Scope ignored = AreaExportConfigOverride.begin(
                    new AreaExportConfigOverride.Values(false, 0, false))) {
                nativeResult = mapExporter.export(processor, processor.worldBiomeRegistry,
                        processor.getWorldDimensionTypeRegistry(), selection, saveLoad.getOldFormatSupport());
            }
            if (nativeResult == null || !nativeResult.getType().isSuccess())
                throw new IOException("Xaero map rendering failed: " + (nativeResult == null ? "no result" : nativeResult.getType().name()));
            List<Path> rawFiles;
            try (var paths = Files.walk(jobDirectory)) {
                rawFiles = paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT).endsWith(".png")).toList();
            }
            if (rawFiles.size() != 1) throw new IOException("Expected one rendered map image, found " + rawFiles.size());
            BufferedImage raw = ImageIO.read(rawFiles.get(0).toFile());
            if (raw == null || raw.getWidth() != rawWidth || raw.getHeight() != rawHeight)
                throw new IOException("Xaero returned an unexpected image size");
            int cropX = Math.toIntExact(bounds.minX() - minChunkX * 16L);
            int cropZ = Math.toIntExact(bounds.minZ() - minChunkZ * 16L);
            BufferedImage output = compose(raw, cropX, cropZ, Math.toIntExact(width), Math.toIntExact(height),
                    territory, overlays, groups);
            if (!ImageIO.write(output, "png", tempFinal.toFile())) throw new IOException("PNG writer is unavailable");
            try {
                Files.move(tempFinal, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFinal, finalPath);
            }
            return new Result(finalPath, output.getWidth(), output.getHeight());
        } finally {
            exporterAccess.exactselection$setDestinationPath(previousDestination);
            saveLoadAccess.exactselection$setExporting(false);
            Files.deleteIfExists(tempFinal);
            deleteOwnedTree(jobDirectory);
        }
    }

    private static BufferedImage compose(BufferedImage raw, int cropX, int cropZ, int width, int height,
                                         AreaGeometry territory, List<SavedAreaStore.Area> areas,
                                         List<SavedAreaStore.Group> groups) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            Map<String, Boolean> groupVisibility = new HashMap<>();
            for (SavedAreaStore.Group group : groups) groupVisibility.put(group.id(), group.visible());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Path2D.Double clip = path(territory, territory.bounds().minX(), territory.bounds().minZ());
            graphics.clip(clip);
            graphics.drawImage(raw, -cropX, -cropZ, null);
            List<VisibleOverlay> visibleOverlays = new java.util.ArrayList<>();
            java.awt.geom.Area territoryShape = new java.awt.geom.Area(clip);
            for (SavedAreaStore.Area area : areas) {
                if (!area.visible() || !groupVisibility.getOrDefault(area.groupId(), true)) continue;
                AreaGeometry geometry = area.geometry();
                if (!geometry.bounds().intersectsPositiveArea(territory.bounds())) continue;
                Path2D.Double areaPath = path(geometry, territory.bounds().minX(), territory.bounds().minZ());
                java.awt.geom.Area visibleShape = new java.awt.geom.Area(areaPath);
                visibleShape.intersect(new java.awt.geom.Area(territoryShape));
                if (visibleShape.isEmpty()) continue;
                graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, area.opacity() / 100.0f));
                graphics.setColor(new Color(area.color() & 0xFFFFFF));
                graphics.fill(areaPath);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setStroke(new BasicStroke(1.0f));
                graphics.draw(areaPath);
                visibleOverlays.add(new VisibleOverlay(area, visibleShape));
            }
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            for (VisibleOverlay overlay : visibleOverlays) {
                SavedAreaStore.Area area = overlay.area();
                if (area.geometry().bounds().width() > 24 && area.geometry().bounds().length() > 12)
                    drawAreaLabel(graphics, area, overlay.visibleShape());
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void drawAreaLabel(Graphics2D graphics, SavedAreaStore.Area area,
                                      java.awt.geom.Area visibleShape) {
        LabelLayout layout = findAreaLabel(graphics, area.name(), visibleShape);
        if (layout == null) return;
        var text = graphics.getFont().createGlyphVector(graphics.getFontRenderContext(), area.name())
                .getOutline(layout.x(), layout.y());
        var bounds = layout.bounds();
        graphics.setColor(new Color(12, 18, 24, 230));
        graphics.fillRoundRect(bounds.x - 5, bounds.y - 4, bounds.width + 10, bounds.height + 8, 5, 5);
        graphics.setColor(new Color(area.color() & 0xFFFFFF));
        graphics.fillRect(bounds.x - 5, bounds.y - 2, 2, bounds.height + 4);
        graphics.setColor(Color.BLACK);
        graphics.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(text);
        graphics.setColor(Color.WHITE);
        graphics.fill(text);
    }

    private static LabelLayout findAreaLabel(Graphics2D graphics, String name,
                                             java.awt.geom.Area visibleShape) {
        Rectangle2D visibleBounds = visibleShape.getBounds2D();
        var metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(name);
        if (visibleBounds.getWidth() < textWidth + 10 || visibleBounds.getHeight() < metrics.getHeight() + 10)
            return null;
        double minX = visibleBounds.getMinX() + 6;
        double maxX = visibleBounds.getMaxX() - textWidth - 6;
        double minY = visibleBounds.getMinY() + metrics.getAscent() + 6;
        double maxY = visibleBounds.getMaxY() - metrics.getDescent() - 6;
        double preferredX = visibleBounds.getMinX() + 6;
        double preferredY = visibleBounds.getMinY() + metrics.getAscent() + 6;
        List<Double> xs = labelSamples(minX, maxX, preferredX);
        List<Double> ys = labelSamples(minY, maxY, preferredY);
        LabelLayout best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        var glyphs = graphics.getFont().createGlyphVector(graphics.getFontRenderContext(), name);
        for (double y : ys) for (double x : xs) {
            var outline = glyphs.getOutline((float) x, (float) y);
            java.awt.Rectangle textBounds = outline.getBounds();
            Rectangle2D backing = new Rectangle2D.Double(textBounds.x - 5, textBounds.y - 4,
                    textBounds.width + 10, textBounds.height + 8);
            if (!visibleShape.contains(backing)) continue;
            double dx = x - preferredX;
            double dy = y - preferredY;
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new LabelLayout((float) x, (float) y, textBounds);
            }
        }
        return best;
    }

    private static List<Double> labelSamples(double minimum, double maximum, double preferred) {
        if (maximum < minimum) return List.of();
        List<Double> samples = new java.util.ArrayList<>(9);
        samples.add(Math.max(minimum, Math.min(maximum, preferred)));
        if (maximum > minimum) for (int i = 0; i <= 6; i++) samples.add(minimum + (maximum - minimum) * i / 6.0);
        return samples.stream().distinct().sorted(Comparator.comparingDouble(value -> Math.abs(value - preferred))).toList();
    }

    private static Path2D.Double path(AreaGeometry geometry, long originX, long originZ) {
        List<GridPoint> vertices = geometry.vertices();
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO, vertices.size());
        GridPoint first = vertices.get(0);
        path.moveTo(first.x() - originX, first.z() - originZ);
        for (int i = 1; i < vertices.size(); i++) {
            GridPoint point = vertices.get(i);
            path.lineTo(point.x() - originX, point.z() - originZ);
        }
        path.closePath();
        return path;
    }

    private static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
