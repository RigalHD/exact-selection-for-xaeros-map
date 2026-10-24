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
                new RectangleGeometry(new SelectionBounds(0, 0, 15, 15)), List.of(), List.of());
    }

    private static boolean leftovers(Path root, String id) throws IOException {
        try (var files = Files.list(root)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith(".") && path.toString().contains(id));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("exact-selection-export-");
        try {
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
            System.out.println("PASS: background export writes the PNG, survives cleanup failures, and keeps the original error");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
