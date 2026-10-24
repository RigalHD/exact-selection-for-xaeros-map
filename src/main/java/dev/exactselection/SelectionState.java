package dev.exactselection;

public final class SelectionState {
    public enum Phase { OFF, READY, PREVIEW, SELECTED }
    public record Snapshot(Phase phase, SelectionBounds bounds, AreaGeometry geometry) {
        public Snapshot(Phase phase, SelectionBounds bounds) {
            this(phase, bounds, bounds == null ? null : new RectangleGeometry(bounds));
        }
    }
    private Phase phase = Phase.OFF;
    private SelectionBounds bounds;
    private AreaGeometry geometry;
    public Phase phase() { return phase; }
    public boolean enabled() { return phase != Phase.OFF; }
    public SelectionBounds bounds() { return bounds; }
    public AreaGeometry geometry() { return geometry; }
    public Snapshot snapshot() { return new Snapshot(phase, bounds, geometry); }
    public void restore(Snapshot snapshot) {
        phase = snapshot.phase(); bounds = snapshot.bounds();
        geometry = snapshot.geometry() != null ? snapshot.geometry()
                : bounds == null ? null : new RectangleGeometry(bounds);
    }
    public void enable() { phase = Phase.READY; bounds = null; geometry = null; }
    public void disable() { phase = Phase.OFF; bounds = null; geometry = null; }
    public void reset() { bounds = null; geometry = null; if (enabled()) phase = Phase.READY; }
    public void click(int x, int z) {
        if (!enabled()) return;
        if (phase == Phase.PREVIEW) {
            bounds = new SelectionBounds(bounds.x1(), bounds.z1(), x, z);
            geometry = new RectangleGeometry(bounds);
            phase = Phase.SELECTED;
        } else {
            bounds = new SelectionBounds(x, z, x, z);
            geometry = new RectangleGeometry(bounds);
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
        if (geometry == null) { bounds = null; phase = enabled() ? Phase.READY : Phase.OFF; return; }
        GeometryBounds b = geometry.bounds();
        bounds = new SelectionBounds((int) b.minX(), (int) b.minZ(), (int) (b.maxX() - 1), (int) (b.maxZ() - 1));
        phase = Phase.SELECTED;
    }
    public void escape() { if (bounds != null) reset(); else disable(); }
}
