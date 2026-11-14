package dev.exactselection;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.exactselection.ScreenGeometry.Point;
import dev.exactselection.ScreenGeometry.Vertex;
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

/** Draws shapes on the World Map screen: filled triangles clipped to the screen and smoothed outlines. */
public final class GeometryScreenRenderer {
    /** Width of the smoothed outline edge when the GUI scale is unknown, in GUI units. */
    private static final double DEFAULT_FRINGE = 0.5;

    private GeometryScreenRenderer() {}

    public static void draw(
            GuiGraphics graphics,
            MapBridge.Frame frame,
            AreaGeometry geometry,
            int width,
            int height,
            int fillColor,
            int outlineColor,
            double outlineWidth) {
        draw(
                ((GuiGraphicsBridge) graphics).exactselection$renderState(),
                graphics.pose(),
                frame,
                geometry,
                width,
                height,
                fillColor,
                outlineColor,
                outlineWidth);
    }

    /** One physical pixel in GUI units: the outline fades out over it so diagonal sides do not show steps. */
    private static double fringe() {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getGuiScale();
        return scale > 0 ? 1.0 / scale : DEFAULT_FRINGE;
    }

    static void draw(
            GuiRenderState state,
            Matrix3x2fc transform,
            MapBridge.Frame frame,
            AreaGeometry geometry,
            int width,
            int height,
            int fillColor,
            int outlineColor,
            double outlineWidth) {
        List<Point> viewport = viewport(width, height);
        List<Vertex> fill = new ArrayList<>();
        if (fillColor >>> 24 != 0) {
            for (List<GridPoint> triangle : geometry.triangulate()) {
                List<Point> points =
                        triangle.stream().map(p -> project(p, frame, transform)).toList();
                List<Point> clipped = ScreenGeometry.clipPolygon(points, viewport);
                if (clipped.size() < 3) continue;
                for (int i = 1; i < clipped.size() - 1; i++) {
                    triangle(
                            fill,
                            new Vertex(clipped.get(0), fillColor),
                            new Vertex(clipped.get(i), fillColor),
                            new Vertex(clipped.get(i + 1), fillColor));
                }
            }
        }
        submit(state, fill, width, height);

        List<Vertex> outline = new ArrayList<>();
        if (outlineColor >>> 24 != 0) {
            List<GridPoint> vertices = geometry.vertices();
            for (int i = 0; i < (geometry.closed() ? vertices.size() : vertices.size() - 1); i++) {
                Point[] line = ScreenGeometry.clipSegment(
                        project(vertices.get(i), frame, transform),
                        project(vertices.get((i + 1) % vertices.size()), frame, transform),
                        viewport);
                if (line != null)
                    ScreenGeometry.stroke(
                            line[0],
                            line[1],
                            outlineWidth / 2.0,
                            fringe(),
                            outlineColor,
                            (a, b, c) -> triangle(outline, a, b, c));
            }
        }
        submit(state, outline, width, height);
    }

    public static void drawPolyline(
            GuiGraphics graphics,
            MapBridge.Frame frame,
            List<GridPoint> points,
            GridPoint cursor,
            int width,
            int height,
            int color) {
        drawPolyline(
                ((GuiGraphicsBridge) graphics).exactselection$renderState(),
                graphics.pose(),
                frame,
                points,
                cursor,
                width,
                height,
                color);
    }

    static void drawPolyline(
            GuiRenderState state,
            Matrix3x2fc transform,
            MapBridge.Frame frame,
            List<GridPoint> points,
            GridPoint cursor,
            int width,
            int height,
            int color) {
        if (points.isEmpty() || color >>> 24 == 0) return;
        List<Vertex> builder = new ArrayList<>();
        List<Point> viewport = viewport(width, height);
        for (int i = 0; i < points.size() - 1 + (cursor == null ? 0 : 1); i++) {
            GridPoint start = points.get(i % points.size());
            GridPoint end = i < points.size() - 1 ? points.get(i + 1) : cursor;
            if (end == null) break;
            Point[] line = ScreenGeometry.clipSegment(
                    project(start, frame, transform), project(end, frame, transform), viewport);
            if (line != null)
                ScreenGeometry.stroke(line[0], line[1], 0.75, fringe(), color, (a, b, c) -> triangle(builder, a, b, c));
        }
        submit(state, builder, width, height);
    }

    /** A smoothed line between two screen points, as wide as a contour line. */
    public static void drawSegment(
            GuiGraphics graphics, double x1, double y1, double x2, double y2, int width, int height, int color) {
        Matrix3x2fc transform = graphics.pose();
        Point[] line = ScreenGeometry.clipSegment(
                transform(x1, y1, transform), transform(x2, y2, transform), viewport(width, height));
        if (line == null || color >>> 24 == 0) return;
        List<Vertex> builder = new ArrayList<>();
        ScreenGeometry.stroke(line[0], line[1], 0.75, fringe(), color, (a, b, c) -> triangle(builder, a, b, c));
        submit(((GuiGraphicsBridge) graphics).exactselection$renderState(), builder, width, height);
    }

    private static List<Point> viewport(int width, int height) {
        return List.of(new Point(0, 0), new Point(width, 0), new Point(width, height), new Point(0, height));
    }

    private static Point project(GridPoint point, MapBridge.Frame frame, Matrix3x2fc matrix) {
        return transform(frame.screenX(point.x()), frame.screenY(point.z()), matrix);
    }

    private static Point transform(double x, double y, Matrix3x2fc matrix) {
        return new Point(
                matrix.m00() * x + matrix.m10() * y + matrix.m20(), matrix.m01() * x + matrix.m11() * y + matrix.m21());
    }

    private static void triangle(List<Vertex> vertices, Vertex a, Vertex b, Vertex c) {
        double winding = ScreenGeometry.cross(a.point(), b.point(), c.point());
        if (Math.abs(winding) < 1.0E-8) return;
        if (winding > 0) {
            Vertex swap = b;
            b = c;
            c = swap;
        }
        vertices.add(a);
        vertices.add(b);
        vertices.add(c);
        vertices.add(c);
    }

    private static void submit(GuiRenderState state, List<Vertex> vertices, int width, int height) {
        if (vertices.isEmpty()) return;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Vertex vertex : vertices) {
            Point point = vertex.point();
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }
        ScreenRectangle viewport = new ScreenRectangle(0, 0, width, height);
        ScreenRectangle bounds = new ScreenRectangle(
                        (int) Math.floor(minX),
                        (int) Math.floor(minY),
                        (int) Math.ceil(maxX) - (int) Math.floor(minX),
                        (int) Math.ceil(maxY) - (int) Math.floor(minY))
                .intersection(viewport);
        if (bounds != null) state.submitGuiElement(new GeometryState(List.copyOf(vertices), viewport, bounds));
    }

    private record GeometryState(List<Vertex> vertices, ScreenRectangle scissorArea, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (Vertex vertex : vertices)
                consumer.addVertex(
                                (float) vertex.point().x(),
                                (float) vertex.point().y(),
                                0)
                        .setColor(vertex.color());
        }
    }
}
