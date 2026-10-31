package dev.exactselection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PolygonGeometry(List<GridPoint> vertices) implements AreaGeometry {
    public static final int MAX_VERTICES = 256;
    private static final Map<List<GridPoint>, List<List<GridPoint>>> TRIANGULATIONS =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<List<GridPoint>, List<List<GridPoint>>> eldest) {
                    return size() > 1024;
                }
            });

    public PolygonGeometry {
        vertices = List.copyOf(vertices);
        validate(vertices);
    }

    @Override public GeometryBounds bounds() {
        long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (GridPoint p : vertices) {
            minX = Math.min(minX, p.x()); minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x()); maxZ = Math.max(maxZ, p.z());
        }
        return new GeometryBounds(minX, minZ, maxX, maxZ);
    }

    @Override public long areaTwice() { return Math.abs(signedAreaTwice(vertices)); }

    @Override public boolean containsPoint(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return false;
        boolean inside = false;
        for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
            GridPoint a = vertices.get(j), b = vertices.get(i);
            double cross = (b.x() - a.x()) * (z - a.z()) - (b.z() - a.z()) * (x - a.x());
            if (Math.abs(cross) < 1.0E-9 && x >= Math.min(a.x(), b.x()) && x <= Math.max(a.x(), b.x())
                    && z >= Math.min(a.z(), b.z()) && z <= Math.max(a.z(), b.z())) return true;
            if ((a.z() > z) != (b.z() > z) && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x())
                inside = !inside;
        }
        return inside;
    }

    @Override public AreaGeometry translated(long dx, long dz) {
        List<GridPoint> moved = new ArrayList<>(vertices.size());
        for (GridPoint p : vertices) moved.add(new GridPoint(Math.addExact(p.x(), dx), Math.addExact(p.z(), dz)));
        return new PolygonGeometry(moved);
    }

    @Override public List<List<GridPoint>> triangulate() {
        synchronized (TRIANGULATIONS) {
            List<List<GridPoint>> cached = TRIANGULATIONS.get(vertices);
            if (cached != null) return cached;
            List<List<GridPoint>> result = triangulateUncached();
            TRIANGULATIONS.put(vertices, result);
            return result;
        }
    }

    private List<List<GridPoint>> triangulateUncached() {
        List<GridPoint> points = simplifyCollinear(vertices);
        boolean ccw = signedAreaTwice(points) > 0;
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) remaining.add(i);
        List<List<GridPoint>> triangles = new ArrayList<>(points.size() - 2);
        int guard = points.size() * points.size();
        while (remaining.size() > 3 && guard-- > 0) {
            boolean clipped = false;
            for (int i = 0; i < remaining.size(); i++) {
                int prev = remaining.get((i + remaining.size() - 1) % remaining.size());
                int cur = remaining.get(i);
                int next = remaining.get((i + 1) % remaining.size());
                GridPoint a = points.get(prev), b = points.get(cur), c = points.get(next);
                long cross = cross(a, b, c);
                if ((ccw && cross <= 0) || (!ccw && cross >= 0)) continue;
                boolean contains = false;
                for (int index : remaining) {
                    if (index == prev || index == cur || index == next) continue;
                    if (insideTriangle(points.get(index), a, b, c, ccw)) { contains = true; break; }
                }
                if (contains) continue;
                triangles.add(List.of(a, b, c));
                remaining.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) throw new IllegalStateException("Polygon could not be triangulated");
        }
        if (remaining.size() != 3) throw new IllegalStateException("Polygon triangulation did not finish");
        triangles.add(List.of(points.get(remaining.get(0)), points.get(remaining.get(1)), points.get(remaining.get(2))));
        return List.copyOf(triangles);
    }

    private static List<GridPoint> simplifyCollinear(List<GridPoint> input) {
        List<GridPoint> result = new ArrayList<>(input);
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < result.size() && result.size() > 3; i++) {
                GridPoint a = result.get((i + result.size() - 1) % result.size());
                GridPoint b = result.get(i);
                GridPoint c = result.get((i + 1) % result.size());
                if (cross(a, b, c) == 0 && between(a.x(), b.x(), c.x()) && between(a.z(), b.z(), c.z())) {
                    result.remove(i);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    private static void validate(List<GridPoint> points) {
        if (points.size() < 3 || points.size() > MAX_VERTICES) throw new IllegalArgumentException("Polygon needs 3 to 256 vertices");
        for (int i = 0; i < points.size(); i++) {
            GridPoint a = points.get(i), b = points.get((i + 1) % points.size());
            if (a.equals(b)) throw new IllegalArgumentException("Polygon has a zero-length edge");
            for (int j = i + 1; j < points.size(); j++) {
                if (a.equals(points.get(j))) throw new IllegalArgumentException("Polygon repeats a vertex");
            }
        }
        if (signedAreaTwice(points) == 0) throw new IllegalArgumentException("Polygon has zero area");
        int n = points.size();
        for (int i = 0; i < n; i++) {
            GridPoint a = points.get((i + n - 1) % n), b = points.get(i), c = points.get((i + 1) % n);
            if (cross(a, b, c) == 0 && (!between(a.x(), b.x(), c.x()) || !between(a.z(), b.z(), c.z())))
                throw new IllegalArgumentException("Polygon overlaps itself at an adjacent edge");
        }
        for (int i = 0; i < n; i++) {
            GridPoint a = points.get(i), b = points.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                if (j == (i + 1) % n || i == (j + 1) % n) continue;
                GridPoint c = points.get(j), d = points.get((j + 1) % n);
                if (segmentsIntersect(a, b, c, d)) throw new IllegalArgumentException("Polygon edges intersect or touch");
            }
        }
    }

    private static boolean segmentsIntersect(GridPoint a, GridPoint b, GridPoint c, GridPoint d) {
        long abC = cross(a, b, c), abD = cross(a, b, d), cdA = cross(c, d, a), cdB = cross(c, d, b);
        if (((abC > 0 && abD < 0) || (abC < 0 && abD > 0)) && ((cdA > 0 && cdB < 0) || (cdA < 0 && cdB > 0))) return true;
        return abC == 0 && onSegment(a, c, b) || abD == 0 && onSegment(a, d, b)
                || cdA == 0 && onSegment(c, a, d) || cdB == 0 && onSegment(c, b, d);
    }

    private static boolean onSegment(GridPoint a, GridPoint p, GridPoint b) {
        return p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
                && p.z() >= Math.min(a.z(), b.z()) && p.z() <= Math.max(a.z(), b.z());
    }

    private static boolean insideTriangle(GridPoint p, GridPoint a, GridPoint b, GridPoint c, boolean ccw) {
        long ab = cross(a, b, p), bc = cross(b, c, p), ca = cross(c, a, p);
        return ccw ? ab >= 0 && bc >= 0 && ca >= 0 : ab <= 0 && bc <= 0 && ca <= 0;
    }

    private static long signedAreaTwice(List<GridPoint> points) {
        long area = 0;
        for (int i = 0; i < points.size(); i++) {
            GridPoint a = points.get(i), b = points.get((i + 1) % points.size());
            area = Math.addExact(area, Math.subtractExact(Math.multiplyExact(a.x(), b.z()), Math.multiplyExact(b.x(), a.z())));
        }
        return area;
    }

    private static long cross(GridPoint a, GridPoint b, GridPoint c) {
        return Math.subtractExact(Math.multiplyExact(b.x() - a.x(), c.z() - a.z()),
                Math.multiplyExact(b.z() - a.z(), c.x() - a.x()));
    }

    private static boolean between(long a, long b, long c) { return b >= Math.min(a, c) && b <= Math.max(a, c); }
}
