package dev.exactselection;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2fc;

public final class GeometryScreenRenderer {
    private record Point(double x, double y) {}
    private record Vertex(Point point, int color) {}
    /** Width of the smoothed outline edge when the GUI scale is unknown, in GUI units. */
    private static final double DEFAULT_FRINGE = 0.5;

    private GeometryScreenRenderer() {}

    public static void draw(GuiGraphics graphics, MapBridge.Frame frame, AreaGeometry geometry,
                            int width, int height, int fillColor, int outlineColor, double outlineWidth) {
        draw(((GuiGraphicsBridge) graphics).exactselection$renderState(), graphics.pose(), frame, geometry,
                width, height, fillColor, outlineColor, outlineWidth, fringe());
    }

    static void draw(GuiRenderState state, Matrix3x2fc transform, MapBridge.Frame frame, AreaGeometry geometry,
                     int width, int height, int fillColor, int outlineColor, double outlineWidth) {
        draw(state, transform, frame, geometry, width, height, fillColor, outlineColor, outlineWidth, DEFAULT_FRINGE);
    }

    /** One physical pixel in GUI units: the outline fades out over it so diagonal sides do not show steps. */
    private static double fringe() {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getGuiScale();
        return scale > 0 ? 1.0 / scale : DEFAULT_FRINGE;
    }

    private static void draw(GuiRenderState state, Matrix3x2fc transform, MapBridge.Frame frame, AreaGeometry geometry,
                             int width, int height, int fillColor, int outlineColor, double outlineWidth, double fringe) {
        List<Point> viewport = List.of(new Point(0, 0), new Point(width, 0),
                new Point(width, height), new Point(0, height));
        List<Vertex> fill = new ArrayList<>();
        if (fillColor >>> 24 != 0) {
            for (List<GridPoint> triangle : geometry.triangulate()) {
                List<Point> points = triangle.stream().map(p -> project(p, frame, transform)).toList();
                List<Point> clipped = clipPolygon(points, viewport);
                if (clipped.size() < 3) continue;
                for (int i = 1; i < clipped.size() - 1; i++) {
                    triangle(fill, new Vertex(clipped.get(0), fillColor), new Vertex(clipped.get(i), fillColor),
                            new Vertex(clipped.get(i + 1), fillColor));
                }
            }
        }
        submit(state, fill, width, height);

        List<Vertex> outline = new ArrayList<>();
        if (outlineColor >>> 24 != 0) {
            List<GridPoint> vertices = geometry.vertices();
            for (int i = 0; i < vertices.size(); i++) {
                Point[] line = clipSegment(project(vertices.get(i), frame, transform),
                        project(vertices.get((i + 1) % vertices.size()), frame, transform), width, height);
                if (line != null) stroke(outline, line[0], line[1], outlineWidth / 2.0, fringe, outlineColor);
            }
        }
        submit(state, outline, width, height);
    }

    /**
     * A segment {@code 2 * half} wide whose sides fade to transparent over {@code fringe}, so a diagonal side is
     * smooth instead of stepped; the solid core is narrower by the fringe so the line keeps its weight.
     */
    private static void stroke(List<Vertex> vertices, Point start, Point end, double half, double fringe, int color) {
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double length = Math.hypot(dx, dy);
        if (length < 0.01) return;
        double nx = -dy / length, ny = dx / length;
        double core = Math.max(0, half - fringe / 2), outer = core + fringe;
        int clear = color & 0x00FFFFFF;
        band(vertices, start, end, nx, ny, -core, core, color, color);
        band(vertices, start, end, nx, ny, core, outer, color, clear);
        band(vertices, start, end, nx, ny, -outer, -core, clear, color);
    }

    /** The part of a segment between offsets {@code from} and {@code to} along its normal, colored across it. */
    private static void band(List<Vertex> vertices, Point start, Point end, double nx, double ny,
                             double from, double to, int fromColor, int toColor) {
        Vertex a = new Vertex(new Point(start.x() + nx * from, start.y() + ny * from), fromColor);
        Vertex b = new Vertex(new Point(start.x() + nx * to, start.y() + ny * to), toColor);
        Vertex c = new Vertex(new Point(end.x() + nx * to, end.y() + ny * to), toColor);
        Vertex d = new Vertex(new Point(end.x() + nx * from, end.y() + ny * from), fromColor);
        triangle(vertices, a, b, c);
        triangle(vertices, a, c, d);
    }

    public static void drawPolyline(GuiGraphics graphics, MapBridge.Frame frame, List<GridPoint> points,
                                    GridPoint cursor, int width, int height, int color) {
        drawPolyline(((GuiGraphicsBridge) graphics).exactselection$renderState(), graphics.pose(), frame,
                points, cursor, width, height, color, fringe());
    }

    static void drawPolyline(GuiRenderState state, Matrix3x2fc transform, MapBridge.Frame frame, List<GridPoint> points,
                             GridPoint cursor, int width, int height, int color) {
        drawPolyline(state, transform, frame, points, cursor, width, height, color, DEFAULT_FRINGE);
    }

    private static void drawPolyline(GuiRenderState state, Matrix3x2fc transform, MapBridge.Frame frame,
                                     List<GridPoint> points, GridPoint cursor, int width, int height, int color,
                                     double fringe) {
        if (points.isEmpty() || color >>> 24 == 0) return;
        List<Vertex> builder = new ArrayList<>();
        for (int i = 0; i < points.size() - 1 + (cursor == null ? 0 : 1); i++) {
            GridPoint start = points.get(i % points.size());
            GridPoint end = i < points.size() - 1 ? points.get(i + 1) : cursor;
            if (end == null) break;
            Point[] line = clipSegment(project(start, frame, transform), project(end, frame, transform), width, height);
            if (line != null) stroke(builder, line[0], line[1], 0.75, fringe, color);
        }
        submit(state, builder, width, height);
    }

    private static Point project(GridPoint point, MapBridge.Frame frame, Matrix3x2fc matrix) {
        double x = frame.screenX(point.x()), y = frame.screenY(point.z());
        return new Point(matrix.m00() * x + matrix.m10() * y + matrix.m20(),
                matrix.m01() * x + matrix.m11() * y + matrix.m21());
    }

    private static List<Point> clipPolygon(List<Point> subject, List<Point> clip) {
        List<Point> output = new ArrayList<>(subject);
        for (int i = 0; i < clip.size() && !output.isEmpty(); i++) {
            Point a = clip.get(i), b = clip.get((i + 1) % clip.size());
            List<Point> input = output;
            output = new ArrayList<>();
            Point previous = input.get(input.size() - 1);
            double previousDistance = cross(a, b, previous);
            for (Point current : input) {
                double currentDistance = cross(a, b, current);
                boolean previousInside = previousDistance >= 0;
                boolean currentInside = currentDistance >= 0;
                if (currentInside != previousInside) output.add(intersection(previous, current, previousDistance, currentDistance));
                if (currentInside) output.add(current);
                previous = current;
                previousDistance = currentDistance;
            }
        }
        return output;
    }

    private static Point[] clipSegment(Point a, Point b, int width, int height) {
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a.x(), width - a.x(), a.y(), height - a.y()};
        double start = 0, end = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1.0E-12) { if (q[i] < 0) return null; continue; }
            double t = q[i] / p[i];
            if (p[i] < 0) start = Math.max(start, t); else end = Math.min(end, t);
            if (start > end) return null;
        }
        return new Point[]{new Point(a.x() + dx * start, a.y() + dy * start),
                new Point(a.x() + dx * end, a.y() + dy * end)};
    }

    private static Point intersection(Point a, Point b, double da, double db) {
        double t = da / (da - db);
        return new Point(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    private static double cross(Point a, Point b, Point p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    private static void triangle(List<Vertex> vertices, Vertex a, Vertex b, Vertex c) {
        double winding = cross(a.point(), b.point(), c.point());
        if (Math.abs(winding) < 1.0E-8) return;
        if (winding > 0) { Vertex swap = b; b = c; c = swap; }
        vertices.add(a); vertices.add(b); vertices.add(c); vertices.add(c);
    }

    private static void submit(GuiRenderState state, List<Vertex> vertices, int width, int height) {
        if (vertices.isEmpty()) return;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Vertex vertex : vertices) {
            Point point = vertex.point();
            minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y());
        }
        ScreenRectangle viewport = new ScreenRectangle(0, 0, width, height);
        ScreenRectangle bounds = new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.ceil(maxX) - (int) Math.floor(minX),
                (int) Math.ceil(maxY) - (int) Math.floor(minY)).intersection(viewport);
        if (bounds != null) state.submitGuiElement(new GeometryState(List.copyOf(vertices), viewport, bounds));
    }

    private record GeometryState(List<Vertex> vertices, ScreenRectangle scissorArea, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
        @Override public void buildVertices(VertexConsumer consumer) {
            for (Vertex vertex : vertices)
                consumer.addVertex((float) vertex.point().x(), (float) vertex.point().y(), 0).setColor(vertex.color());
        }
    }
}
