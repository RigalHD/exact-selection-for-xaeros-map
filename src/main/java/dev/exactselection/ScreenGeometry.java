package dev.exactselection;

import java.util.ArrayList;
import java.util.List;

/** Clipping and smoothed lines in screen space, shared by the world map and the minimap. */
final class ScreenGeometry {
    record Point(double x, double y) {}
    record Vertex(Point point, int color) {}
    @FunctionalInterface interface Triangles { void add(Vertex a, Vertex b, Vertex c); }

    private ScreenGeometry() {}

    /** The part of {@code subject} inside the convex polygon {@code clip}, which may wind either way. */
    static List<Point> clipPolygon(List<Point> subject, List<Point> clip) {
        List<Point> output = new ArrayList<>(subject);
        double orientation = signedArea(clip) >= 0 ? 1 : -1;
        for (int i = 0; i < clip.size() && !output.isEmpty(); i++) {
            Point a = clip.get(i), b = clip.get((i + 1) % clip.size());
            List<Point> input = output;
            output = new ArrayList<>();
            Point previous = input.get(input.size() - 1);
            double previousDistance = orientation * cross(a, b, previous);
            for (Point current : input) {
                double currentDistance = orientation * cross(a, b, current);
                boolean previousInside = previousDistance >= -1.0E-7;
                boolean currentInside = currentDistance >= -1.0E-7;
                if (currentInside != previousInside)
                    output.add(interpolate(previous, current, previousDistance / (previousDistance - currentDistance)));
                if (currentInside) output.add(current);
                previous = current;
                previousDistance = currentDistance;
            }
        }
        return output;
    }

    /** The part of a segment inside the convex polygon {@code clip}, or null when none is. */
    static Point[] clipSegment(Point start, Point end, List<Point> clip) {
        double orientation = signedArea(clip) >= 0 ? 1 : -1;
        double startT = 0, endT = 1;
        for (int i = 0; i < clip.size(); i++) {
            Point a = clip.get(i), b = clip.get((i + 1) % clip.size());
            double d0 = orientation * cross(a, b, start);
            double d1 = orientation * cross(a, b, end);
            if (d0 < 0 && d1 < 0) return null;
            if (d0 < 0 || d1 < 0) {
                double t = d0 / (d0 - d1);
                if (d0 < 0) startT = Math.max(startT, t);
                else endT = Math.min(endT, t);
            }
        }
        if (startT > endT) return null;
        return new Point[]{interpolate(start, end, startT), interpolate(start, end, endT)};
    }

    /**
     * A segment {@code 2 * half} wide whose sides fade to transparent over {@code fringe}, so a diagonal side is
     * smooth instead of stepped; the solid core is narrower by the fringe so the line keeps its weight.
     */
    static void stroke(Point start, Point end, double half, double fringe, int color, Triangles out) {
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double length = Math.hypot(dx, dy);
        if (length < 0.01) return;
        double nx = -dy / length, ny = dx / length;
        double core = Math.max(0, half - fringe / 2), outer = core + fringe;
        int clear = color & 0x00FFFFFF;
        band(start, end, nx, ny, -core, core, color, color, out);
        band(start, end, nx, ny, core, outer, color, clear, out);
        band(start, end, nx, ny, -outer, -core, clear, color, out);
    }

    /** The part of a segment between offsets {@code from} and {@code to} along its normal, colored across it. */
    private static void band(Point start, Point end, double nx, double ny, double from, double to,
                             int fromColor, int toColor, Triangles out) {
        Vertex a = new Vertex(new Point(start.x() + nx * from, start.y() + ny * from), fromColor);
        Vertex b = new Vertex(new Point(start.x() + nx * to, start.y() + ny * to), toColor);
        Vertex c = new Vertex(new Point(end.x() + nx * to, end.y() + ny * to), toColor);
        Vertex d = new Vertex(new Point(end.x() + nx * from, end.y() + ny * from), fromColor);
        out.add(a, b, c);
        out.add(a, c, d);
    }

    static double cross(Point a, Point b, Point p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    private static Point interpolate(Point a, Point b, double t) {
        return new Point(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    private static double signedArea(List<Point> polygon) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Point a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += a.x() * b.y() - b.x() * a.y();
        }
        return area / 2.0;
    }
}
