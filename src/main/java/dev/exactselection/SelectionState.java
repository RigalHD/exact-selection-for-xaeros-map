package dev.exactselection;

public final class SelectionState {
    public enum Phase { OFF, READY, PREVIEW, SELECTED }
    public record Snapshot(Phase phase, SelectionBounds bounds, AreaGeometry geometry, String source) {
        public Snapshot(Phase phase, SelectionBounds bounds, AreaGeometry geometry) {
            this(phase, bounds, geometry, null);
        }
        public Snapshot(Phase phase, SelectionBounds bounds) {
            this(phase, bounds, bounds == null ? null : new RectangleGeometry(bounds));
        }
    }
    private Phase phase = Phase.OFF;
    private SelectionBounds bounds;
    private AreaGeometry geometry;
    private String source;
    public Phase phase() { return phase; }
    public boolean enabled() { return phase != Phase.OFF; }
    public SelectionBounds bounds() { return bounds; }
    public AreaGeometry geometry() { return geometry; }
    public String source() { return source; }
    public Snapshot snapshot() { return new Snapshot(phase, bounds, geometry, source); }
    public void restore(Snapshot snapshot) {
        phase = snapshot.phase(); bounds = snapshot.bounds(); source = snapshot.source();
        geometry = snapshot.geometry() != null ? snapshot.geometry()
                : bounds == null ? null : new RectangleGeometry(bounds);
    }
    public void enable() { phase = Phase.READY; bounds = null; geometry = null; source = null; }
    public void disable() { phase = Phase.OFF; bounds = null; geometry = null; source = null; }
    public void reset() { bounds = null; geometry = null; source = null; if (enabled()) phase = Phase.READY; }
    public void click(int x, int z) {
        if (!enabled()) return;
        if (phase == Phase.PREVIEW) {
            bounds = new SelectionBounds(bounds.x1(), bounds.z1(), x, z);
            geometry = new RectangleGeometry(bounds);
            phase = Phase.SELECTED;
        } else {
            // A new selection no longer edits the saved area that was loaded.
            bounds = new SelectionBounds(x, z, x, z);
            geometry = new RectangleGeometry(bounds);
            source = null;
            phase = Phase.PREVIEW;
        }
    }
    public void hover(int x, int z) {
        if (phase == Phase.PREVIEW) {
            bounds = new SelectionBounds(bounds.x1(), bounds.z1(), x, z);
            geometry = new RectangleGeometry(bounds);
        }
    }
    public void hover(int x1, int z1, int x2, int z2) {
        if (phase == Phase.PREVIEW) {
            bounds = new SelectionBounds(x1, z1, x2, z2);
            geometry = new RectangleGeometry(bounds);
        }
    }
    public void set(SelectionBounds bounds) {
        this.bounds = bounds; geometry = bounds == null ? null : new RectangleGeometry(bounds); phase = Phase.SELECTED;
    }
    public void setGeometry(AreaGeometry geometry) {
        this.geometry = geometry;
        if (geometry == null) { bounds = null; source = null; phase = enabled() ? Phase.READY : Phase.OFF; return; }
        bounds = geometry.bounds().blocks();
        phase = Phase.SELECTED;
    }
    /** The saved area this selection edited is gone: the shape stays as a new selection. */
    public void detach() { source = null; }
    public void load(AreaGeometry geometry, String source) {
        setGeometry(geometry);
        this.source = geometry == null ? null : source;
    }
}
