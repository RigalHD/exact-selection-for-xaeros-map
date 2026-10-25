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
import java.io.UncheckedIOException;
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
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    /**
     * What the export window chose: target folder and file name, Xaero's night lighting, image pixels per block,
     * saved-area overlays and labels, and the game's pixel font (null draws labels with a plain system font).
     */
    public record Options(Path folder, String fileName, boolean night, int scale, boolean labels, boolean areas, PixelFont font) {}
    private record VisibleOverlay(SavedAreaStore.Area area, java.awt.geom.Area visibleShape) {}
    public static final long MAX_PIXELS = 8_000_000L;
    /** Upper bound for the scaled output image, which is held in memory as 4 bytes per pixel. */
    public static final long MAX_OUTPUT_PIXELS = 16_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("Exact Selection for Xaero's map");

    private AreaExportService() {}

    /**
     * Renders the native Xaero image on the client thread, then crops, composes and writes the PNG in the
     * background so a large export does not freeze the game. Validation and native rendering errors are thrown
     * directly; later failures complete the returned future exceptionally.
     */
    public static CompletableFuture<Result> export(MapProcessor processor, MapBridge.Context context, AreaGeometry territory,
                                                   List<SavedAreaStore.Area> overlays, List<SavedAreaStore.Group> groups,
                                                   Options options) throws Exception {
        RawJob job = renderNative(processor, context, territory, overlays, groups, options);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return finish(job); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }, Util.backgroundExecutor());
        } catch (RuntimeException rejected) {
            cleanUp(rejected, job.jobDirectory());
            throw rejected;
        }
    }

    /** A native Xaero image in its own job directory, waiting to be cropped and composed. */
    record RawJob(Path jobDirectory, Path exportRoot, String id, long rawWidth, long rawHeight, int cropX, int cropZ,
                  int width, int height, AreaGeometry territory, List<SavedAreaStore.Area> overlays,
                  List<SavedAreaStore.Group> groups, Options options) {}

    /** Whether a shape of these bounds can be exported at {@code scale} image pixels per block. */
    public static boolean fits(GeometryBounds bounds, int scale) {
        long width = bounds.width(), height = bounds.length();
        return width * height <= MAX_PIXELS && width * scale * height * scale <= MAX_OUTPUT_PIXELS;
    }

    /** Xaero's own export folder, where the window starts unless the user chose another one; null when not ready. */
    public static Path defaultFolder(MapProcessor processor) {
        if (processor == null) return null;
        try {
            var exporter = ((MapSaveLoadAccessor) processor.getMapSaveLoad()).exactselection$getPngExporter();
            Path folder = ((PNGExporterAccessor) exporter).exactselection$getDestinationPath();
            return folder == null ? null : folder.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static RawJob renderNative(MapProcessor processor, MapBridge.Context context, AreaGeometry territory,
                                       List<SavedAreaStore.Area> overlays, List<SavedAreaStore.Group> groups,
                                       Options options) throws Exception {
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
        if (width < 1 || height < 1 || pixels > MAX_PIXELS || !fits(bounds, options.scale()))
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
        saveLoadAccess.exactselection$setExporting(true);
        try {
            exporterAccess.exactselection$setDestinationPath(jobDirectory);
            MapTileSelection selection = new MapTileSelection(Math.toIntExact(minChunkX), Math.toIntExact(minChunkZ));
            selection.setEnd(Math.toIntExact(maxChunkX), Math.toIntExact(maxChunkZ));
            PNGExportResult nativeResult;
            try (AreaExportConfigOverride.Scope ignored = AreaExportConfigOverride.begin(
                    new AreaExportConfigOverride.Values(false, 0, false, options.night()))) {
                nativeResult = mapExporter.export(processor, processor.worldBiomeRegistry,
                        processor.getWorldDimensionTypeRegistry(), selection, saveLoad.getOldFormatSupport());
            }
            if (nativeResult == null || !nativeResult.getType().isSuccess())
                throw new IOException("Xaero map rendering failed: " + (nativeResult == null ? "no result" : nativeResult.getType().name()));
            return new RawJob(jobDirectory, exportRoot, id, rawWidth, rawHeight,
                    Math.toIntExact(bounds.minX() - minChunkX * 16L), Math.toIntExact(bounds.minZ() - minChunkZ * 16L),
                    Math.toIntExact(width), Math.toIntExact(height), territory, overlays, groups, options);
        } catch (Throwable failure) {
            cleanUp(failure, jobDirectory);
            throw failure;
        } finally {
            exporterAccess.exactselection$setDestinationPath(previousDestination);
            saveLoadAccess.exactselection$setExporting(false);
        }
    }

    /** Off the client thread: everything here only touches the job's own files and immutable area data. */
    static Result finish(RawJob job) throws IOException {
        Path folder = job.options().folder();
        Files.createDirectories(folder);
        Path finalPath = uniqueFile(folder, job.options().fileName());
        Path tempFinal = folder.resolve(".ExactSelection-" + job.id() + ".tmp");
        try {
            List<Path> rawFiles;
            try (var paths = Files.walk(job.jobDirectory())) {
                rawFiles = paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT).endsWith(".png")).toList();
            }
            if (rawFiles.size() != 1) throw new IOException("Expected one rendered map image, found " + rawFiles.size());
            BufferedImage raw = ImageIO.read(rawFiles.get(0).toFile());
            if (raw == null || raw.getWidth() != job.rawWidth() || raw.getHeight() != job.rawHeight())
                throw new IOException("Xaero returned an unexpected image size");
            BufferedImage output = compose(raw, job.cropX(), job.cropZ(), job.width(), job.height(),
                    job.territory(), job.options().areas() ? job.overlays() : List.of(), job.groups(), job.options().scale(), job.options().labels(),
                    job.options().font());
            if (!ImageIO.write(output, "png", tempFinal.toFile())) throw new IOException("PNG writer is unavailable");
            try {
                Files.move(tempFinal, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFinal, finalPath);
            }
            // The PNG is complete; leftover temporary files must not turn this into a failed export.
            cleanUp(null, tempFinal, job.jobDirectory());
            return new Result(finalPath, output.getWidth(), output.getHeight());
        } catch (Throwable failure) {
            cleanUp(failure, tempFinal, job.jobDirectory());
            throw failure;
        }
    }

    /** Best-effort removal: errors are attached to the export failure, or only logged when the export succeeded. */
    private static void cleanUp(Throwable failure, Path... paths) {
        for (Path path : paths) {
            try { deleteOwnedTree(path); }
            catch (IOException | UncheckedIOException e) {
                if (failure != null) failure.addSuppressed(e);
                else LOGGER.warn("Exported map is ready, but temporary files could not be removed: {}", path, e);
            }
        }
    }

    private static Path uniqueFile(Path folder, String name) {
        String base = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        Path file = folder.resolve(base + ".png");
        for (int i = 2; Files.exists(file); i++) file = folder.resolve(base + "-" + i + ".png");
        return file;
    }

    private static BufferedImage compose(BufferedImage raw, int cropX, int cropZ, int width, int height,
                                         AreaGeometry territory, List<SavedAreaStore.Area> areas,
                                         List<SavedAreaStore.Group> groups) {
        return compose(raw, cropX, cropZ, width, height, territory, areas, groups, 1, true, null);
    }

    /**
     * Draws the map like the in-game World Map: Xaero's pixels enlarged without smoothing, then each visible area
     * as a translucent fill with a thin outline, then names in the area's color on a dark backing, above all fills.
     */
    private static BufferedImage compose(BufferedImage raw, int cropX, int cropZ, int width, int height,
                                         AreaGeometry territory, List<SavedAreaStore.Area> areas,
                                         List<SavedAreaStore.Group> groups, int scale, boolean labels, PixelFont font) {
        BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            Map<String, Boolean> groupVisibility = new HashMap<>();
            for (SavedAreaStore.Group group : groups) groupVisibility.put(group.id(), group.visible());
            // Hard pixel edges, as on the in-game map.
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            long originX = territory.bounds().minX(), originZ = territory.bounds().minZ();
            Path2D.Double clip = path(territory, originX, originZ, scale);
            graphics.clip(clip);
            graphics.drawImage(raw, -cropX * scale, -cropZ * scale, raw.getWidth() * scale, raw.getHeight() * scale, null);
            List<VisibleOverlay> visibleOverlays = new java.util.ArrayList<>();
            java.awt.geom.Area territoryShape = new java.awt.geom.Area(clip);
            float outline = Math.max(1, scale / 2);
            for (SavedAreaStore.Area area : areas) {
                if (!area.visible() || !groupVisibility.getOrDefault(area.groupId(), true)) continue;
                AreaGeometry geometry = area.geometry();
                if (!geometry.bounds().intersectsPositiveArea(territory.bounds())) continue;
                Path2D.Double areaPath = path(geometry, originX, originZ, scale);
                java.awt.geom.Area visibleShape = new java.awt.geom.Area(areaPath);
                visibleShape.intersect(territoryShape);
                if (visibleShape.isEmpty()) continue;
                graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, area.opacity() / 100.0f));
                graphics.setColor(new Color(area.color() & 0xFFFFFF));
                graphics.fill(areaPath);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setStroke(new BasicStroke(outline, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                graphics.draw(areaPath);
                visibleOverlays.add(new VisibleOverlay(area, visibleShape));
            }
            if (labels) {
                int textScale = Math.max(1, scale / 2);
                for (VisibleOverlay overlay : visibleOverlays) drawAreaLabel(graphics, overlay, font, textScale);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * The in-game map label: the name in the area's color with Minecraft's drop shadow on a dark backing, centred
     * near the top of the area's visible part and only where the whole backing fits inside it.
     */
    private static void drawAreaLabel(Graphics2D graphics, VisibleOverlay overlay, PixelFont font, int scale) {
        String name = overlay.area().name();
        boolean pixel = font != null && font.supports(name);
        Font fallback = new Font(Font.SANS_SERIF, Font.PLAIN, 8 * scale);
        int textWidth = pixel ? font.width(name) * scale : graphics.getFontMetrics(fallback).stringWidth(name);
        int boxWidth = textWidth + 4 * scale, boxHeight = (PixelFont.LINE_HEIGHT + 2) * scale;
        Rectangle2D visible = overlay.visibleShape().getBounds2D();
        // The in-game map labels an area once it is wider than 50 and taller than 14 GUI pixels on screen.
        if (visible.getWidth() <= 50 * scale || visible.getHeight() <= 14 * scale) return;
        double preferredX = visible.getCenterX() - boxWidth / 2.0, preferredY = visible.getMinY() + 3 * scale;
        java.awt.Rectangle best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (double y : samples(visible.getMinY(), visible.getMaxY() - boxHeight, preferredY))
            for (double x : samples(visible.getMinX(), visible.getMaxX() - boxWidth, preferredX)) {
                java.awt.Rectangle box = new java.awt.Rectangle((int) Math.round(x), (int) Math.round(y), boxWidth, boxHeight);
                if (!overlay.visibleShape().contains(box)) continue;
                double distance = (x - preferredX) * (x - preferredX) + (y - preferredY) * (y - preferredY);
                if (distance < bestDistance) { bestDistance = distance; best = box; }
            }
        if (best == null) {
            // Like the in-game map: centred at the top of the area even when the name is wider than the area.
            java.awt.Rectangle image = graphics.getDeviceConfiguration().getBounds();
            int x = (int) Math.round(Math.max(0, Math.min(image.width - boxWidth, preferredX)));
            best = new java.awt.Rectangle(x, (int) Math.round(preferredY), boxWidth, boxHeight);
        }
        graphics.setColor(new Color(0x10, 0x18, 0x20, 0xB0));
        graphics.fillRect(best.x, best.y, best.width, best.height);
        int color = readable(overlay.area().color());
        if (pixel) {
            font.draw(graphics, name, best.x + 2 * scale, best.y + scale, scale, 0xFF000000 | color);
        } else {
            graphics.setFont(fallback);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            int baseline = best.y + scale + graphics.getFontMetrics().getAscent();
            graphics.setColor(new Color((color & 0xFCFCFC) >> 2));
            graphics.drawString(name, best.x + 3 * scale, baseline + scale);
            graphics.setColor(new Color(color));
            graphics.drawString(name, best.x + 2 * scale, baseline);
        }
    }

    /** Lifts very dark area colors so their names stay legible on the dark backing. */
    private static int readable(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        if (0.2126 * r + 0.7152 * g + 0.0722 * b >= 90) return rgb & 0xFFFFFF;
        return (r + 255) / 2 << 16 | (g + 255) / 2 << 8 | (b + 255) / 2;
    }

    private static List<Double> samples(double minimum, double maximum, double preferred) {
        if (maximum < minimum) return List.of();
        List<Double> values = new java.util.ArrayList<>(10);
        values.add(Math.max(minimum, Math.min(maximum, preferred)));
        if (maximum > minimum) for (int i = 0; i <= 8; i++) values.add(minimum + (maximum - minimum) * i / 8.0);
        return values.stream().distinct().sorted(Comparator.comparingDouble(value -> Math.abs(value - preferred))).toList();
    }

    private static Path2D.Double path(AreaGeometry geometry, long originX, long originZ, int scale) {
        List<GridPoint> vertices = geometry.vertices();
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO, vertices.size());
        GridPoint first = vertices.get(0);
        path.moveTo((first.x() - originX) * (double) scale, (first.z() - originZ) * (double) scale);
        for (int i = 1; i < vertices.size(); i++) {
            GridPoint point = vertices.get(i);
            path.lineTo((point.x() - originX) * (double) scale, (point.z() - originZ) * (double) scale);
        }
        path.closePath();
        return path;
    }

    private static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        IOException first = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { if (first == null) first = e; else first.addSuppressed(e); }
            }
        }
        if (first != null) throw first;
    }
}
