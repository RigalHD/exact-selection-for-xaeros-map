package dev.exactselection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.ModLog.LOG;

/**
 * The PNG export window: target folder, day or night, scale, saved areas and labels. The controller shows it as one
 * of its dialogs and runs the export it describes.
 */
final class ExportWindow {
    final ExportPreferences preferences;
    private final Minecraft mc;
    private final Supplier<Path> defaultFolder;
    EditBox folder;
    Button pick, standardFolder, openFolder, time, scale, labels, areas, ownFill, run;
    private AreaGeometry target;
    /** The saved area being exported, whose fill can be left out; null for a plain selection. */
    private String targetArea;
    private Component title = Component.empty();
    /** The file this window writes, named when it opened so the path it shows is the one it uses. */
    private String fileName = newFileName();
    /** This window's scale: the remembered one, lowered until the target fits; only the Scale button changes the preference. */
    int windowScale;
    /** Minecraft's pixel font, read once from the game's resources; null when they are unavailable. */
    private PixelFont pixelFont;
    private boolean pixelFontLoaded;

    ExportWindow(Minecraft mc, ExportPreferences preferences, Supplier<Path> defaultFolder) {
        this.mc = mc;
        this.preferences = preferences;
        this.defaultFolder = defaultFolder;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("exactselection." + key, args);
    }

    private static Button button(String key, Runnable action) {
        return Button.builder(tr(key), b -> action.run()).bounds(0, 0, 100, 18).build();
    }

    private static Button button(String key, String tooltipKey, Runnable action) {
        Button result = button(key, action);
        result.setTooltip(Tooltip.create(tr(tooltipKey)));
        return result;
    }

    /**
     * Creates the widgets again after Xaero rebuilds the screen, keeping a typed folder. {@code export} runs the
     * export and {@code report} shows a message.
     */
    List<AbstractWidget> createWidgets(Runnable export, Consumer<String> report) {
        String typed = folder == null ? preferences.folder : folder.getValue();
        folder = new EditBox(mc.font, 0, 0, 100, 18, Component.empty());
        folder.setMaxLength(1024);
        folder.setValue(typed);
        pick = button("export_pick_folder", "export_pick_tooltip", () -> pickFolder(report));
        standardFolder = button("export_default_folder", () -> folder.setValue(""));
        openFolder = button("import_open_folder", () -> {
            Path target = folder();
            if (target != null && Files.isDirectory(target)) Util.getPlatform().openPath(target);
        });
        time = button("export_day", "export_time_tooltip", () -> { preferences.night = !preferences.night; preferences.save(); });
        scale = button("export_scale", "export_scale_tooltip", () -> {
            int[] scales = ExportPreferences.SCALES;
            int index = 0;
            for (int i = 0; i < scales.length; i++) if (scales[i] == windowScale) index = i;
            windowScale = preferences.scale = scales[(index + 1) % scales.length];
            preferences.save();
        });
        labels = button("export_labels_on", "export_labels_tooltip", () -> { preferences.labels = !preferences.labels; preferences.save(); });
        areas = button("export_areas_on", "export_areas_tooltip", () -> { preferences.areas = !preferences.areas; preferences.save(); });
        ownFill = button("export_own_fill_on", "export_own_fill_tooltip", () -> { preferences.ownFill = !preferences.ownFill; preferences.save(); });
        run = button("export_run", export);
        return List.of(folder, pick, standardFolder, openFolder, time, scale, areas, labels, ownFill, run);
    }

    void open(AreaGeometry target, Component title, String targetArea) {
        this.target = target;
        this.targetArea = targetArea;
        this.title = title;
        fileName = newFileName();
        // Start from the largest scale up to the remembered one that the area fits at.
        windowScale = preferences.scale;
        while (windowScale > 1 && !AreaExportService.fits(target.bounds(), windowScale)) windowScale /= 2;
        folder.setValue(preferences.folder);
    }

    AreaGeometry target() { return target; }

    boolean hasTargetArea() { return targetArea != null; }

    /** The saved area to export without its fill, or null when every area keeps its fill. */
    String unfilledArea() { return targetArea != null && preferences.areas && !preferences.ownFill ? targetArea : null; }

    /** The folder typed in the window (empty: the default one), or null when it is not an absolute path. */
    Path folder() {
        String typed = folder.getValue().trim();
        if (typed.isEmpty()) return defaultFolder.get();
        try {
            Path path = Path.of(typed);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String newFileName() {
        return "ExactSelection-" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)) + ".png";
    }

    /** The system folder picker; it blocks the game until it closes, like other mods' file dialogs. */
    private void pickFolder(Consumer<String> report) {
        try {
            Path current = folder();
            String chosen = TinyFileDialogs.tinyfd_selectFolderDialog(
                    tr("export_pick_title").getString(), current == null ? "" : current.toString());
            if (chosen != null && !chosen.isBlank()) folder.setValue(chosen);
        } catch (Throwable unavailable) {
            report.accept(tr("export_pick_unavailable").getString());
        }
    }

    /** Remembers the choices and describes the export, or returns null when the folder is not usable. */
    AreaExportService.Options options() {
        Path target = folder();
        if (target == null || this.target == null) return null;
        preferences.folder = folder.getValue().isBlank() || target.equals(defaultFolder.get()) ? "" : target.toString();
        preferences.save();
        if (!pixelFontLoaded) {
            pixelFontLoaded = true;
            try {
                pixelFont = PixelFont.load(path -> mc.getResourceManager()
                        .getResource(Identifier.withDefaultNamespace(path)).orElseThrow().open());
            } catch (Exception e) {
                LOG.warn("Minecraft font unavailable; labels use a system font", e);
            }
        }
        return new AreaExportService.Options(target, fileName, preferences.night, windowScale, preferences.labels,
                preferences.areas, pixelFont);
    }

    void layout(int x, int y, int w, int buttonY) {
        int half = (w - 27) / 2;
        place(folder, x + 12, y + 56, w - 24 - 94, 18);
        place(pick, x + w - 102, y + 56, 90, 18);
        place(standardFolder, x + 12, y + 78, half, 18);
        place(openFolder, x + 15 + half, y + 78, w - 27 - half, 18);
        place(time, x + 12, y + 122, half, 18);
        place(scale, x + 15 + half, y + 122, w - 27 - half, 18);
        place(areas, x + 12, y + 144, half, 18);
        place(labels, x + 15 + half, y + 144, w - 27 - half, 18);
        place(ownFill, x + 12, y + 166, w - 24, 18);
        place(run, x + w / 2 - 116, buttonY, 110, 18);
    }

    void show(boolean exportRunning) {
        for (AbstractWidget widget : List.of(folder, pick, standardFolder, openFolder, time, scale, areas, labels, run))
            widget.visible = true;
        time.setMessage(tr(preferences.night ? "export_night" : "export_day"));
        scale.setMessage(tr("export_scale", windowScale));
        labels.setMessage(tr(preferences.labels ? "export_labels_on" : "export_labels_off"));
        areas.setMessage(tr(preferences.areas ? "export_areas_on" : "export_areas_off"));
        labels.active = ownFill.active = preferences.areas;
        ownFill.visible = hasTargetArea();
        ownFill.setMessage(tr(preferences.ownFill ? "export_own_fill_on" : "export_own_fill_off"));
        Path target = folder();
        openFolder.active = target != null && Files.isDirectory(target);
        run.active = target != null && this.target != null && !exportRunning
                && AreaExportService.fits(this.target.bounds(), windowScale);
    }

    void render(GuiGraphics g, int x, int y, int w, String message) {
        GeometryBounds b = target == null ? null : target.bounds();
        if (b != null) g.drawString(mc.font, mc.font.plainSubstrByWidth(Component.literal(title.getString() + " · ")
                .append(tr("export_blocks", b.width(), b.length())).getString(), w - 24), x + 12, y + 30, -1);
        g.drawString(mc.font, tr("export_folder"), x + 12, y + 46, 0xFF9BAAB7);
        Path target = folder();
        String file = target == null ? tr("export_folder_invalid").getString() : target.resolve(fileName).toString();
        // Keep the end of a long path, where the file name is.
        String shown = tail(file, w - 24 - mc.font.width(tr("export_file", "").getString()));
        g.drawString(mc.font, tr("export_file", shown), x + 12, y + 104, target == null ? 0xFFFFCF62 : 0xFF9BAAB7);
        if (b != null) {
            boolean fits = AreaExportService.fits(b, windowScale);
            g.drawString(mc.font, fits ? tr("export_size", b.width() * windowScale, b.length() * windowScale)
                    : tr("export_size_too_large", AreaExportService.MAX_OUTPUT_PIXELS), x + 12, y + (hasTargetArea() ? 190 : 168), fits ? 0xFF9BAAB7 : 0xFFFFCF62);
        }
        if (!message.isEmpty()) g.drawString(mc.font, mc.font.plainSubstrByWidth(message, w - 24), x + 12, y + (hasTargetArea() ? 204 : 182), 0xFFFFCF62);
    }

    /** The end of a text that fits {@code width}, with an ellipsis in place of what was cut from its start. */
    private String tail(String text, int width) {
        if (mc.font.width(text) <= width) return text;
        return "…" + mc.font.plainSubstrByWidth(text, Math.max(0, width - mc.font.width("…")), true);
    }

    /** Drawn above the widgets: the default folder stays greyed in the empty field; the end of the path is kept. */
    void renderPlaceholder(GuiGraphics g, boolean focused) {
        if (!folder.visible || !folder.getValue().isEmpty() || focused) return;
        String path = tail(defaultFolder.get().toString(), folder.getWidth() - 8);
        g.drawString(mc.font, path, folder.getX() + 4, folder.getY() + (folder.getHeight() - 8) / 2, 0xFF707A84);
    }
}
