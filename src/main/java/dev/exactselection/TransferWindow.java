package dev.exactselection;

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
import static dev.exactselection.ModLog.LOG;

/** Area exchange files: exporting areas and groups, and the import window. */
final class TransferWindow extends UiPart {
    Button areaExportFile, areaImport, groupExport, importOpenFolder, transferFolderButton;
    /** Exchange files listed by the import window, newest first; refreshed each time it opens. */
    List<Path> importFiles = List.of();
    /** Each listed file's date, read when the window opens instead of from disk every frame. */
    private Map<Path, String> importDates = Map.of();
    int importScroll;

    TransferWindow(ExactSelectionController ui) { super(ui); }

    private Path transferFolder() { return AreaTransfer.folder(ui.areaStore.root()); }

    private void openTransferFolder() {
        try { Files.createDirectories(transferFolder()); } catch (IOException ignored) { }
        Util.getPlatform().openPath(transferFolder());
    }

    /** Exports the checked areas, or every area of this world when none is checked. */
    private void exportAreaFile() {
        if (ui.loadFailed) return;
        List<Area> areas = ui.areasWindow.selectedAreas().isEmpty() ? ui.areas() : ui.areasWindow.selectedAreas();
        exportTransfer(ui.areasWindow.selectedAreas().isEmpty() ? "areas" : areas.size() == 1 ? areas.get(0).name() : "areas", areas);
    }

    private void exportGroupFile() {
        Group group = ui.groupsWindow.selectedGroup();
        if (group == null || ui.loadFailed) return;
        exportTransfer(group.name(), ui.areas().stream().filter(a -> Objects.equals(a.groupId(), group.id())).toList());
    }

    private void exportTransfer(String label, List<Area> areas) {
        if (areas.isEmpty()) return;
        try {
            Path file = AreaTransfer.export(transferFolder(), label, areas, ui.groups());
            ui.mapExport.lastExport = file.toAbsolutePath().normalize();
            ui.mapExport.exportNotice = ui.tr("areas_exported", areas.size(), file.getFileName().toString()).getString();
            ui.message = ui.mapExport.exportNotice;
            ui.mapExport.setExportTooltip(Component.literal(ui.mapExport.lastExport.toString()));
        } catch (Exception e) {
            ui.say("areas_export_failed");
            LOG.error("Could not export areas", e);
        }
    }

    private void openImport() {
        if (ui.loadFailed) return;
        ui.areaEdits.flushDraft();
        try { importFiles = AreaTransfer.list(transferFolder()); }
        catch (Exception e) { importFiles = List.of(); }
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());
        importDates = new HashMap<>();
        for (Path file : importFiles) {
            try { importDates.put(file, time.format(Files.getLastModifiedTime(file).toInstant())); }
            catch (Exception e) { /* The list shows no date for it. */ }
        }
        importScroll = 0;
        ui.dialog = Dialog.IMPORT;
        ui.message = "";
        ui.focus(null);
    }

    static int importRows(int height) { return Math.max(1, (height - 84) / 20); }

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
        g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.tr("import_hint").getString(), w - 24), x + 12, y + 32, 0xFF9BAAB7);
        int rows = importRows(h);
        importScroll = Math.min(importScroll, Math.max(0, importFiles.size() - rows));
        g.fill(x + 10, y + 46, x + w - 10, y + 48 + rows * 20, 0xFF0C141D);
        if (importFiles.isEmpty()) g.drawString(ui.mc.font, ui.tr("import_empty"), x + 16, y + 54, 0xFF9BAAB7);
        for (int i = 0; i < rows && i + importScroll < importFiles.size(); i++) {
            Path file = importFiles.get(i + importScroll);
            int rowY = y + 48 + i * 20;
            String when = importDates.getOrDefault(file, "");
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(file.getFileName().toString(), w - 150), x + 16, rowY + 6, -1);
            g.drawString(ui.mc.font, when, x + w - 16 - ui.mc.font.width(when), rowY + 6, 0xFF9BAAB7);
        }
        if (!ui.message.isEmpty()) g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.message, w - 190), x + 172, y + h - 23, 0xFFFFCF62);
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
