package dev.exactselection;

import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.ModLog.LOG;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Area exchange files: exporting areas and groups, and the import window. */
final class TransferWindow extends UiPart {
    Button areaExportFile, areaImport, groupExport, importOpenFolder, transferFolderButton;
    /** Exchange files listed by the import window, newest first; refreshed each time it opens. */
    List<Path> importFiles = List.of();
    /** Each listed file's date, read when the window opens instead of from disk every frame. */
    private Map<Path, String> importDates = Map.of();

    int importScroll;

    final Window importWindow = new Window() {
        @Override
        int width() {
            return 560;
        }

        @Override
        int height() {
            return 330;
        }

        @Override
        String title() {
            return "import_title";
        }

        @Override
        boolean titleOnLeft() {
            return true;
        }

        @Override
        Dialog returnTo() {
            return Dialog.AREAS;
        }

        @Override
        void show() {
            ui.show(true, ui.managementClose, importOpenFolder);
        }

        @Override
        void layout(Rect r, int buttonY) {
            int x = r.x(), y = r.y(), w = r.width(), h = r.height();
            importScroll = Math.min(importScroll, Math.max(0, importFiles.size() - importRows(r.height())));
            place(ui.managementClose, x + w - 82, y + 7, 70, 18);
            place(importOpenFolder, x + 12, y + h - 28, 150, 18);
        }

        @Override
        void render(GuiGraphics g, Rect r) {
            renderImportDialog(g, r.x(), r.y(), r.width(), r.height());
        }

        @Override
        void click(MouseButtonEvent event, Rect r) {
            clickImportRow(event, r.x(), r.y(), r.width(), r.height());
        }

        @Override
        void scroll(int step, int height) {
            importScroll = InputRouter.scrolled(importScroll, step, importFiles.size(), importRows(height));
        }
    };

    TransferWindow(ExactSelectionController ui) {
        super(ui);
    }

    private Path transferFolder() {
        return AreaTransfer.folder(ui.areaStore.root());
    }

    private void openTransferFolder() {
        try {
            Files.createDirectories(transferFolder());
        } catch (IOException ignored) {
        }
        Util.getPlatform().openPath(transferFolder());
    }

    /** Exports the checked areas, or every area of this world when none is checked. */
    private void exportAreaFile() {
        if (ui.loadFailed) return;
        List<Area> areas = ui.areasWindow.selectedAreas().isEmpty() ? ui.areas() : ui.areasWindow.selectedAreas();
        exportTransfer(
                ui.areasWindow.selectedAreas().isEmpty()
                        ? "areas"
                        : areas.size() == 1 ? areas.get(0).name() : "areas",
                areas);
    }

    private void exportGroupFile() {
        Group group = ui.groupsWindow.selectedGroup();
        if (group == null || ui.loadFailed) return;
        exportTransfer(
                group.name(),
                ui.areas().stream()
                        .filter(a -> Objects.equals(a.groupId(), group.id()))
                        .toList());
    }

    private void exportTransfer(String label, List<Area> areas) {
        if (areas.isEmpty()) return;
        try {
            Path file = AreaTransfer.export(transferFolder(), label, areas, ui.groups());
            ui.mapExport.announceFile(
                    file,
                    ui.tr("areas_exported", areas.size(), file.getFileName().toString())
                            .getString(),
                    Component.literal(file.toAbsolutePath().normalize().toString()));
        } catch (Exception e) {
            ui.say("areas_export_failed");
            LOG.error("Could not export areas", e);
        }
    }

    private void openImport() {
        if (ui.loadFailed) return;
        ui.areaEdits.flushDraft();
        try {
            importFiles = AreaTransfer.list(transferFolder());
        } catch (Exception e) {
            importFiles = List.of();
            LOG.warn("Could not list the exchange files in {}", transferFolder(), e);
        }
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        importDates = new HashMap<>();
        for (Path file : importFiles) {
            try {
                importDates.put(
                        file, time.format(Files.getLastModifiedTime(file).toInstant()));
            } catch (Exception e) {
                /* The list shows no date for it. */
            }
        }
        importScroll = 0;
        ui.dialog = Dialog.IMPORT;
        ui.message = "";
        ui.focus(null);
    }

    static int importRows(int height) {
        return Math.max(1, (height - 84) / 20);
    }

    /** Adds a file's areas to this world as one undo step and returns to the Areas window. */
    private void importFile(Path file) {
        try {
            AreaTransfer.Merge merge = AreaTransfer.merge(ui.areaStore.state(), AreaTransfer.read(file));
            if (merge.added() > 0 || merge.groupsAdded() > 0) {
                if (!ui.areaEdits.changeAreas(merge.state(), "area_import")) return;
            }
            ui.dialog = Dialog.AREAS;
            ui.say("areas_imported", merge.added(), merge.skipped());
        } catch (Exception e) {
            ui.say("areas_import_failed", file.getFileName().toString());
            LOG.warn("Could not import {}", file, e);
        }
        ui.focus(null);
    }

    void renderImportDialog(GuiGraphics g, int x, int y, int w, int h) {
        g.drawString(
                ui.mc.font,
                ui.mc.font.plainSubstrByWidth(ui.tr("import_hint").getString(), w - 24),
                x + 12,
                y + 32,
                Theme.TEXT_MUTED);
        int rows = importRows(h);
        g.fill(x + 10, y + 46, x + w - 10, y + 48 + rows * 20, Theme.LIST_BACKGROUND);
        if (importFiles.isEmpty()) g.drawString(ui.mc.font, ui.tr("import_empty"), x + 16, y + 54, Theme.TEXT_MUTED);
        for (int i = 0; i < rows && i + importScroll < importFiles.size(); i++) {
            Path file = importFiles.get(i + importScroll);
            int rowY = y + 48 + i * 20;
            String when = importDates.getOrDefault(file, "");
            g.drawString(
                    ui.mc.font,
                    ui.mc.font.plainSubstrByWidth(file.getFileName().toString(), w - 150),
                    x + 16,
                    rowY + 6,
                    -1);
            g.drawString(ui.mc.font, when, x + w - 16 - ui.mc.font.width(when), rowY + 6, Theme.TEXT_MUTED);
        }
        if (!ui.message.isEmpty())
            g.drawString(
                    ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.message, w - 190), x + 172, y + h - 23, Theme.WARNING);
    }

    void clickImportRow(MouseButtonEvent event, int x, int y, int w, int h) {
        int rows = importRows(h);
        if (event.x() >= x + 10 && event.x() < x + w - 10 && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
            int index = importScroll + (int) ((event.y() - y - 48) / 20);
            if (index < importFiles.size()) importFile(importFiles.get(index));
        }
    }

    void createTransferWidgets() {
        areaExportFile = ui.button("areas_export", "areas_export_tooltip", this::exportAreaFile);
        areaImport = ui.button("areas_import", "areas_import_tooltip", this::openImport);
        groupExport = ui.button("group_export", "group_export_tooltip", this::exportGroupFile);
        importOpenFolder = ui.button("import_open_folder", this::openTransferFolder);
        // Folder icon beside the area and group export buttons: the exchange files, like Open folder in map export.
        transferFolderButton = ui.iconButton("transfer_folder_tooltip", this::openTransferFolder);
    }
}
