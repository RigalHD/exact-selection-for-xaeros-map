package dev.exactselection;

import org.junit.jupiter.api.Test;

/** The checks that are still one {@code main} each: a failed one fails its test, and its output names what passed. */
class ChecksTest {
    @Test
    void areaExport() throws Exception {
        AreaExportCheck.main(new String[0]);
    }

    @Test
    void areaRendering() throws Exception {
        AreaRenderingCheck.main(new String[0]);
    }

    @Test
    void exportFinish() throws Exception {
        ExportFinishCheck.main(new String[0]);
    }

    @Test
    void polygonRendering() throws Exception {
        PolygonRenderingCheck.main(new String[0]);
    }

    @Test
    void safeWaypointAccess() throws Exception {
        SafeWaypointAccessCheck.main(new String[0]);
    }

    @Test
    void savedAreaStore() throws Exception {
        SavedAreaStoreCheck.main(new String[0]);
    }

    @Test
    void selection() throws Exception {
        SelectionCheck.main(new String[0]);
    }

    @Test
    void waypointDeletion() throws Exception {
        WaypointDeletionCheck.main(new String[0]);
    }

    @Test
    void waypointEdit() throws Exception {
        WaypointEditCheck.main(new String[0]);
    }
}
