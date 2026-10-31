package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SavedAreaStore.Area;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import static dev.exactselection.ModLog.LOG;

/** Exporting the map to PNG: opening the export window, running the export and reporting its result. */
final class MapExport extends UiPart {
    Button selectionExportButton;
    /** The window the export window returns to. */
    Dialog exportReturn = Dialog.NONE;
    Button openExportButton;
    /** Kept apart from the button, which a window resize creates anew. */
    private Tooltip exportTooltip;
    Path lastExport;
    boolean exportRunning;
    String exportNotice = "";

    MapExport(ExactSelectionController ui) { super(ui); }

    void exportSelectedArea() {
        MapBridge.Frame frame = ui.checkedFrame();
        Area area = ui.areasWindow.selectedArea();
        if (area == null || frame == null || ui.loadFailed || exportRunning) return;
        ui.areaEdits.flushDraft();
        openExportDialog(area.geometry(), ui.tr("export_what_area", area.name()), Dialog.AREAS, area.id());
    }

    boolean canExportSelection() {
        return ui.enabled() && ui.dialog == Dialog.NONE && ui.state.phase() == SelectionState.Phase.SELECTED
                && ui.state.geometry() != null && !ui.dirty && !ui.dragging && ui.gesture == null
                && ui.polygonTool.draft.isEmpty() && !ui.loadFailed && !exportRunning;
    }

    private void exportCurrentSelection() {
        MapBridge.Frame frame = ui.checkedFrame();
        if (frame == null || !canExportSelection()) return;
        Area edited = ui.areasWindow.editedArea();
        openExportDialog(ui.state.geometry(), ui.tr("export_what_selection"), Dialog.NONE, edited == null ? null : edited.id());
    }

    private void openExportDialog(AreaGeometry geometry, Component title, Dialog returnTo, String areaId) {
        ui.exportWindow.open(geometry, title, areaId);
        exportReturn = returnTo;
        ui.dialog = Dialog.EXPORT;
        ui.message = "";
        ui.focus(null);
    }

    /** Xaero's export folder, or the mod's exchange folder before the map has one. */
    Path defaultExportFolder() {
        Path folder = AreaExportService.defaultFolder(ui.bridge.exactselection$processor());
        return folder != null ? folder : ui.areaStore.root().resolve("xaero-exact-selection").resolve("map-exports").toAbsolutePath();
    }

    private void runExport() {
        MapBridge.Frame frame = ui.checkedFrame();
        if (frame == null || exportRunning) return;
        AreaExportService.Options options = ui.exportWindow.options();
        if (options == null) return;
        ui.dialog = exportReturn;
        exportGeometry(frame, ui.exportWindow.target(), options, ui.exportWindow.unfilledArea());
    }

    private void exportGeometry(MapBridge.Frame frame, AreaGeometry geometry, AreaExportService.Options options, String unfilledArea) {
        CompletableFuture<AreaExportService.Result> pendingExport;
        try {
            pendingExport = AreaExportService.export(ui.bridge.exactselection$processor(),
                    frame.context(), geometry, ui.areas().stream().map(a -> !a.id().equals(unfilledArea) ? a
                            : a.withOpacity(0)).toList(), ui.groups(), options);
        } catch (AreaExportService.TooLargeException e) {
            ui.say("area_export_too_large", AreaExportService.MAX_PIXELS);
            return;
        } catch (Exception e) {
            exportFailed(e);
            return;
        }
        exportRunning = true;
        ui.say("area_exporting");
        // The image is composed in the background; the result returns to the client thread for the UI.
        pendingExport.whenComplete((result, failure) -> ui.mc.execute(() -> {
            exportRunning = false;
            if (failure == null) exportCompleted(result);
            else exportFailed(failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure);
        }));
    }

    private void exportFailed(Throwable failure) {
        ui.say("area_export_failed");
        LOG.error("Could not export selected area", failure);
    }

    private void exportCompleted(AreaExportService.Result result) {
        lastExport = result.file().toAbsolutePath().normalize();
        exportNotice = ui.tr("area_exported", result.width(), result.height()).getString();
        ui.message = exportNotice;
        setExportTooltip(Component.literal(lastExport.getFileName().toString()).append(" · ").append(exportNotice));
        ui.selectionPanel.layoutMainPanel();
    }

    void setExportTooltip(Component text) {
        exportTooltip = Tooltip.create(text);
        openExportButton.setTooltip(exportTooltip);
    }

    boolean hasExportNotice() {
        return lastExport != null && !exportNotice.isEmpty() && ui.message.equals(exportNotice);
    }

    private void openLastExport() {
        if (lastExport == null) return;
        if (!Files.isRegularFile(lastExport)) {
            ui.say("export_file_missing");
            return;
        }
        Util.getPlatform().openPath(lastExport.getParent());
    }

    void createExportWidgets() {
        selectionExportButton = ui.button("selection_export", "selection_export_tooltip", this::exportCurrentSelection);
        openExportButton = ui.button("export_show_file", this::openLastExport);
        openExportButton.setTooltip(exportTooltip);
        ui.widgets.addAll(ui.exportWindow.createWidgets(this::runExport, text -> ui.message = text));
    }
}
