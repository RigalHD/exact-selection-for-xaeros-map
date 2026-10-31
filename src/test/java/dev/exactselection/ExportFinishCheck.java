package dev.exactselection;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/** The background half of an export: compose, write, and clean up, including cleanup failures. */
public final class ExportFinishCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static AreaExportService.RawJob job(Path root, String id, int rawSize) throws IOException {
        Path jobDirectory = Files.createDirectories(root.resolve(".raw-" + id));
        BufferedImage raw = new BufferedImage(rawSize, rawSize, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < rawSize; x++) for (int z = 0; z < rawSize; z++) raw.setRGB(x, z, 0xFF336699);
        ImageIO.write(raw, "png", jobDirectory.resolve("xaero.png").toFile());
        return new AreaExportService.RawJob(jobDirectory, root, id, 16, 16, 0, 0, 16, 16,
                new RectangleGeometry(new SelectionBounds(0, 0, 15, 15)), List.of(), List.of(),
                new AreaExportService.Options(root, "ExactSelection-" + id + ".png", false, 1, true, true, null));
    }

    private static void checkHiddenAreas(Path root) throws IOException {
        var area = SavedAreaStore.create("Zone", new SelectionBounds(1, 1, 14, 14), 0xFF0000, 100, null);
        for (boolean showAreas : List.of(true, false)) {
            AreaExportService.RawJob base = job(root, "areas-" + showAreas, 16);
            AreaGeometry territory = new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(16, 0), new GridPoint(0, 16)));
            var options = new AreaExportService.Options(root, "areas-" + showAreas + ".png", false, 1, true, showAreas, null);
            var export = new AreaExportService.RawJob(base.jobDirectory(), root, base.id(), 16, 16, 0, 0, 16, 16,
                    territory, List.of(area), List.of(), options);
            BufferedImage image = ImageIO.read(AreaExportService.finish(export).file().toFile());
            require(image.getRGB(4, 4) == (showAreas ? 0xFFFF0000 : 0xFF336699),
                    "Area export switch did not control the overlay pixels");
            require(image.getRGB(14, 14) == 0, "Hiding areas changed the polygon export boundary");
            if (!showAreas) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int pixel = image.getRGB(x, z);
                require(pixel >>> 24 == 0 || (pixel & 0xFFFFFF) == 0x336699,
                        "An area outline or label remains when areas are hidden");
            }
        }
        System.out.println("PASS: PNG area visibility controls fills, outlines and names without changing the export territory");
    }

    private static void checkRouteInImage(Path root) throws IOException {
        var route = SavedAreaStore.create("Road", new PolylineGeometry(List.of(new GridPoint(2, 2), new GridPoint(13, 2), new GridPoint(13, 13))), 0xFF0000, 100, null);
        AreaExportService.RawJob base = job(root, "route", 16);
        var export = new AreaExportService.RawJob(base.jobDirectory(), root, base.id(), 16, 16, 0, 0, 16, 16, base.territory(), List.of(route), List.of(), base.options());
        BufferedImage image = ImageIO.read(AreaExportService.finish(export).file().toFile());
        require(image.getRGB(8, 2) == 0xFFFF0000 || image.getRGB(8, 1) == 0xFFFF0000, "A route's line is missing from the image");
        require(image.getRGB(8, 8) == 0xFF336699 && image.getRGB(10, 5) == 0xFF336699, "A route was closed or filled in the image");
        System.out.println("PASS: a route is drawn on the PNG as an open line without a fill");
    }

    private static boolean leftovers(Path root, String id) throws IOException {
        try (var files = Files.list(root)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith(".") && path.toString().contains(id));
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = Files.createTempDirectory("exact-selection-export-");
        try {
            checkHiddenAreas(root);
            checkRouteInImage(root);
            AreaExportService.Result result = AreaExportService.finish(job(root, "ok", 16));
            BufferedImage written = ImageIO.read(result.file().toFile());
            require(written.getWidth() == 16 && written.getRGB(4, 4) == 0xFF336699, "Composed PNG has wrong content");
            require(!leftovers(root, "ok"), "Successful export left temporary files");

            AreaExportService.RawJob locked = job(root, "locked", 16);
            Files.setPosixFilePermissions(locked.jobDirectory(), PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                AreaExportService.Result kept = AreaExportService.finish(locked);
                require(Files.isRegularFile(kept.file()), "A cleanup failure discarded the finished PNG");
            } catch (Exception e) {
                throw new AssertionError("A cleanup failure turned a finished export into an error", e);
            } finally {
                Files.setPosixFilePermissions(locked.jobDirectory(), PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            AreaExportService.RawJob wrongSize = job(root, "wrong", 32);
            Files.setPosixFilePermissions(wrongSize.jobDirectory(), PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                AreaExportService.finish(wrongSize);
                throw new AssertionError("An image of the wrong size was accepted");
            } catch (IOException expected) {
                require(expected.getMessage().contains("unexpected image size"),
                        "Cleanup failure replaced the original export error: " + expected);
                require(expected.getSuppressed().length > 0, "The cleanup failure was not attached to the export error");
            } finally {
                Files.setPosixFilePermissions(wrongSize.jobDirectory(), PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            try (var files = Files.list(root)) {
                require(files.noneMatch(path -> path.getFileName().toString().contains("wrong") && path.toString().endsWith(".png")),
                        "A failed export produced a PNG");
            }

            AreaExportService.RawJob failed = job(root, "failed", 32);
            try { AreaExportService.finish(failed); throw new AssertionError("Wrong size accepted"); }
            catch (IOException expected) { require(!leftovers(root, "failed"), "A failed export left temporary files"); }
            // The next export clears the job folder of one the game did not finish, and nothing else.
            Path crashed = Files.createDirectories(root.resolve(".raw-crashed"));
            Files.write(crashed.resolve("xaero.png"), new byte[]{1});
            Path finished = Files.write(root.resolve("finished.png"), new byte[]{1});
            AreaExportService.removeLeftoverJobs(root);
            require(!Files.exists(crashed) && Files.exists(finished), "Leftovers of a stopped export were not cleared correctly");
            System.out.println("PASS: background export writes the PNG, survives cleanup failures, and keeps the original error");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
