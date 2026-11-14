package dev.exactselection;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.exactselection.ScreenGeometry.Point;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
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
        Batch batch = new Batch(graphics.pose(), width, height);
        batch.add(frame, geometry, fillColor, outlineColor, outlineWidth);
        batch.submit(graphics);
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
        Batch batch = new Batch(transform, width, height);
        batch.add(frame, geometry, fillColor, outlineColor, outlineWidth);
        batch.submit(state);
    }

    public static void drawPolyline(
            GuiGraphics graphics,
            MapBridge.Frame frame,
            List<GridPoint> points,
            GridPoint cursor,
            int width,
            int height,
            int color) {
        drawPolyline(renderState(graphics), graphics.pose(), frame, points, cursor, width, height, color);
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
        Batch batch = new Batch(transform, width, height);
        for (int i = 0; i < points.size() - 1 + (cursor == null ? 0 : 1); i++) {
            GridPoint start = points.get(i % points.size());
            GridPoint end = i < points.size() - 1 ? points.get(i + 1) : cursor;
            if (end == null) break;
            batch.line(batch.project(start, frame), batch.project(end, frame), 0.75, color);
        }
        batch.submit(state);
    }

    /** A smoothed line between two screen points, as wide as a contour line. */
    public static void drawSegment(
            GuiGraphics graphics, double x1, double y1, double x2, double y2, int width, int height, int color) {
        if (color >>> 24 == 0) return;
        Batch batch = new Batch(graphics.pose(), width, height);
        batch.line(batch.transform(x1, y1), batch.transform(x2, y2), 0.75, color);
        batch.submit(graphics);
    }

    /** One physical pixel in GUI units: the outline fades out over it so diagonal sides do not show steps. */
    private static double fringe() {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getGuiScale();
        return scale > 0 ? 1.0 / scale : DEFAULT_FRINGE;
    }

    private static GuiRenderState renderState(GuiGraphics graphics) {
        return ((GuiGraphicsBridge) graphics).exactselection$renderState();
    }

    /**
     * Shapes and lines gathered into one GUI draw, in the order they were added, so a later one covers an earlier one.
     * The GUI places each draw above the earlier ones it overlaps by going through them, which takes most of a frame
     * when every one of thousands of saved areas is a draw of its own.
     */
    static final class Batch {
        /** A quad's corners as given, and turned the other way; the GUI splits both along the 0-2 diagonal. */
        private static final int[] FORWARD = {0, 1, 2, 3}, BACKWARD = {0, 3, 2, 1};

        private final Matrix3x2f transform;
        private final int width, height;
        private final double fringe = fringe();
        private List<Point> viewport;
        /** x and y of each vertex in turn, and its color: four vertices make a GUI quad. */
        private float[] xy = new float[512];

        private int[] colors = new int[256];
        private int count;
        private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        /** The corners of the shape being added, on screen: x and y in turn. */
        private double[] corners = new double[32];

        /** Room for the three corners of one triangle, or one point. */
        private final double[] scratch = new double[6];

        Batch(Matrix3x2fc transform, int width, int height) {
            this.transform = new Matrix3x2f(transform);
            this.width = width;
            this.height = height;
        }

        /** A shape's fill and then its outline, {@code outlineWidth} wide. */
        void add(MapBridge.Frame frame, AreaGeometry geometry, int fillColor, int outlineColor, double outlineWidth) {
            int n = projectCorners(frame, geometry);
            boolean inside = true;
            for (int i = 0; i < 2 * n && inside; i += 2)
                inside = corners[i] >= 0 && corners[i] <= width && corners[i + 1] >= 0 && corners[i + 1] <= height;
            if (fillColor >>> 24 != 0) {
                if (!inside) fillClipped(frame, geometry, fillColor);
                // A rectangle is one quad: the GUI splits it along the same diagonal as its two triangles.
                else if (geometry instanceof RectangleGeometry) quad(fillColor);
                else
                    for (List<GridPoint> points : geometry.triangulate()) {
                        for (int i = 0; i < 3; i++) {
                            GridPoint point = points.get(i);
                            screen(scratch, i, frame.screenX(point.x()), frame.screenY(point.z()));
                        }
                        triangle(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch[5], fillColor);
                    }
            }
            if (outlineColor >>> 24 == 0) return;
            for (int i = 0; i < (geometry.closed() ? n : n - 1); i++) {
                int j = (i + 1) % n;
                double x1 = corners[2 * i], y1 = corners[2 * i + 1], x2 = corners[2 * j], y2 = corners[2 * j + 1];
                // A side inside the screen needs no clipping, which is the slow part.
                if (inside) stroke(x1, y1, x2, y2, outlineWidth / 2.0, outlineColor);
                else line(new Point(x1, y1), new Point(x2, y2), outlineWidth / 2.0, outlineColor);
            }
        }

        /** A line {@code 2 * half} wide, clipped to the screen. */
        void line(Point start, Point end, double half, int color) {
            Point[] clipped = ScreenGeometry.clipSegment(start, end, viewport());
            if (clipped != null) stroke(clipped[0].x(), clipped[0].y(), clipped[1].x(), clipped[1].y(), half, color);
        }

        Point project(GridPoint point, MapBridge.Frame frame) {
            return transform(frame.screenX(point.x()), frame.screenY(point.z()));
        }

        Point transform(double x, double y) {
            screen(scratch, 0, x, y);
            return new Point(scratch[0], scratch[1]);
        }

        void submit(GuiGraphics graphics) {
            submit(renderState(graphics));
        }

        /** Hands the vertices to the GUI; nothing is added after this. */
        void submit(GuiRenderState state) {
            if (count == 0) return;
            ScreenRectangle screen = new ScreenRectangle(0, 0, width, height);
            ScreenRectangle bounds = new ScreenRectangle(
                            (int) Math.floor(minX),
                            (int) Math.floor(minY),
                            (int) Math.ceil(maxX) - (int) Math.floor(minX),
                            (int) Math.ceil(maxY) - (int) Math.floor(minY))
                    .intersection(screen);
            if (bounds != null) state.submitGuiElement(new GeometryState(xy, colors, count, screen, bounds));
        }

        private int projectCorners(MapBridge.Frame frame, AreaGeometry geometry) {
            if (geometry instanceof RectangleGeometry rectangle) {
                // The corners of vertices(), without making them.
                GeometryBounds b = rectangle.bounds();
                double left = frame.screenX(b.minX()), right = frame.screenX(b.maxX());
                double top = frame.screenY(b.minZ()), bottom = frame.screenY(b.maxZ());
                screen(corners, 0, left, top);
                screen(corners, 1, right, top);
                screen(corners, 2, right, bottom);
                screen(corners, 3, left, bottom);
                return 4;
            }
            List<GridPoint> vertices = geometry.vertices();
            if (corners.length < 2 * vertices.size()) corners = new double[2 * vertices.size()];
            for (int i = 0; i < vertices.size(); i++)
                screen(
                        corners,
                        i,
                        frame.screenX(vertices.get(i).x()),
                        frame.screenY(vertices.get(i).z()));
            return vertices.size();
        }

        /** Point {@code i} of {@code target}: GUI coordinates {@code x, y} through the transform. */
        private void screen(double[] target, int i, double x, double y) {
            target[2 * i] = transform.m00() * x + transform.m10() * y + transform.m20();
            target[2 * i + 1] = transform.m01() * x + transform.m11() * y + transform.m21();
        }

        private void fillClipped(MapBridge.Frame frame, AreaGeometry geometry, int color) {
            for (List<GridPoint> points : geometry.triangulate()) {
                List<Point> clipped = ScreenGeometry.clipPolygon(
                        List.of(
                                project(points.get(0), frame),
                                project(points.get(1), frame),
                                project(points.get(2), frame)),
                        viewport());
                if (clipped.size() < 3) continue;
                Point origin = clipped.get(0);
                for (int i = 1; i < clipped.size() - 1; i++)
                    triangle(
                            origin.x(),
                            origin.y(),
                            clipped.get(i).x(),
                            clipped.get(i).y(),
                            clipped.get(i + 1).x(),
                            clipped.get(i + 1).y(),
                            color);
            }
        }

        private List<Point> viewport() {
            if (viewport == null)
                viewport =
                        List.of(new Point(0, 0), new Point(width, 0), new Point(width, height), new Point(0, height));
            return viewport;
        }

        /** The shape's four corners as one quad, turned the way the GUI does not cull. */
        private void quad(int color) {
            double winding = cross(corners[0], corners[1], corners[2], corners[3], corners[4], corners[5]);
            if (Math.abs(winding) < 1.0E-8) return;
            for (int i : winding > 0 ? BACKWARD : FORWARD) vertex(corners[2 * i], corners[2 * i + 1], color);
        }

        /** A triangle as a GUI quad that repeats its last vertex, turned the way the GUI does not cull. */
        private void triangle(double ax, double ay, double bx, double by, double cx, double cy, int color) {
            double winding = cross(ax, ay, bx, by, cx, cy);
            if (Math.abs(winding) < 1.0E-8) return;
            vertex(ax, ay, color);
            if (winding > 0) {
                vertex(cx, cy, color);
                vertex(bx, by, color);
                vertex(bx, by, color);
            } else {
                vertex(bx, by, color);
                vertex(cx, cy, color);
                vertex(cx, cy, color);
            }
        }

        /**
         * A segment {@code 2 * half} wide whose sides fade to transparent over a physical pixel, so a diagonal side is
         * smooth instead of stepped; the solid core is narrower by the fringe so the line keeps its weight.
         */
        private void stroke(double x1, double y1, double x2, double y2, double half, int color) {
            double dx = x2 - x1, dy = y2 - y1;
            double length = Math.hypot(dx, dy);
            if (length < 0.01) return;
            double nx = -dy / length, ny = dx / length;
            double core = Math.max(0, half - fringe / 2), outer = core + fringe;
            int clear = color & 0x00FFFFFF;
            band(x1, y1, x2, y2, nx, ny, length, -core, core, color, color);
            band(x1, y1, x2, y2, nx, ny, length, core, outer, color, clear);
            band(x1, y1, x2, y2, nx, ny, length, -outer, -core, clear, color);
        }

        /**
         * The part of a segment between offsets {@code from} and {@code to} along its normal, colored across it. The
         * normal points left of the segment, so the quad always turns the way the GUI does not cull.
         */
        private void band(
                double x1,
                double y1,
                double x2,
                double y2,
                double nx,
                double ny,
                double length,
                double from,
                double to,
                int fromColor,
                int toColor) {
            if ((to - from) * length < 1.0E-8) return;
            vertex(x1 + nx * from, y1 + ny * from, fromColor);
            vertex(x1 + nx * to, y1 + ny * to, toColor);
            vertex(x2 + nx * to, y2 + ny * to, toColor);
            vertex(x2 + nx * from, y2 + ny * from, fromColor);
        }

        private void vertex(double x, double y, int color) {
            if (count == colors.length) {
                colors = Arrays.copyOf(colors, 2 * count);
                xy = Arrays.copyOf(xy, 4 * count);
            }
            xy[2 * count] = (float) x;
            xy[2 * count + 1] = (float) y;
            colors[count++] = color;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        private static double cross(double ax, double ay, double bx, double by, double cx, double cy) {
            return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        }
    }

    /** GUI quads from a {@link Batch}: {@code count} vertices, x and y in {@code xy}. */
    record GeometryState(float[] xy, int[] colors, int count, ScreenRectangle scissorArea, ScreenRectangle bounds)
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
            for (int i = 0; i < count; i++)
                consumer.addVertex(xy[2 * i], xy[2 * i + 1], 0).setColor(colors[i]);
        }
    }
}
