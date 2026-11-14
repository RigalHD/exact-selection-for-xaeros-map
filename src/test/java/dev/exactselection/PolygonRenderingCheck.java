package dev.exactselection;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.ColoredRectangleRenderState;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class PolygonRenderingCheck {
    private record Vertex(float x, float y, float z, int color) {}

    private static final class Capture implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vertex(x, y, z, 0));
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            Vertex v = vertices.remove(vertices.size() - 1);
            vertices.add(new Vertex(v.x(), v.y(), v.z(), color));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor(a << 24 | r << 16 | g << 8 | b);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<GuiElementRenderState> elements(GuiRenderState state) {
        List<GuiElementRenderState> elements = new ArrayList<>();
        state.forEachElement(elements::add, GuiRenderState.TraverseRange.ALL);
        return elements;
    }

    private static Capture capture(GuiElementRenderState element) {
        Capture result = new Capture();
        element.buildVertices(result);
        return result;
    }

    private static double cross(Vertex a, Vertex b, Vertex c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static BufferedImage raster(GuiRenderState state) {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            for (GuiElementRenderState element : elements(state)) {
                require(
                        element.pipeline() == RenderPipelines.GUI,
                        "Polygon bypasses the native GUI pipeline used by its handles");
                require(
                        element.pipeline().getVertexFormatMode() == VertexFormat.Mode.QUADS,
                        "GUI renderer requires quad indices");
                List<Vertex> vertices = capture(element).vertices;
                require(vertices.size() % 4 == 0, "Incomplete GUI quad");
                ScreenRectangle scissor = element.scissorArea();
                graphics.setClip(
                        scissor == null
                                ? null
                                : new java.awt.Rectangle(
                                        scissor.left(), scissor.top(), scissor.width(), scissor.height()));
                for (int i = 0; i < vertices.size(); i += 4) {
                    for (int[] indices : new int[][] {{0, 1, 2}, {2, 3, 0}}) {
                        Vertex a = vertices.get(i + indices[0]),
                                b = vertices.get(i + indices[1]),
                                c = vertices.get(i + indices[2]);
                        require(cross(a, b, c) <= 0.0001, "Polygon faces are culled with the native GUI projection");
                        if (Math.abs(cross(a, b, c)) < 0.0001) continue;
                        Path2D.Float path = new Path2D.Float();
                        path.moveTo(a.x(), a.y());
                        path.lineTo(b.x(), b.y());
                        path.lineTo(c.x(), c.y());
                        path.closePath();
                        graphics.setColor(new Color(a.color(), true));
                        graphics.fill(path);
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Matrix4f projection = new Matrix4f().setOrtho(0, 400, 300, 0, 1000, 11000);
        Vector4f immediate = new Vector4f(200, 150, 0, 1).mul(projection);
        Vector4f nativeGui = new Vector4f(200, 150, -11000, 1).mul(projection);
        require(
                immediate.z() < -1 || immediate.z() > 1,
                "Regression no longer reproduces the clipped immediate polygon");
        require(nativeGui.z() >= -1.00001 && nativeGui.z() <= 1.00001, "Native GUI depth is outside the projection");
        MapBridge.Frame frame = new MapBridge.Frame(
                new MapBridge.Context(new Object(), "render-check", null, "main"), 0, 0, 1, 1, 400, 300);
        List<GridPoint> points = List.of(
                new GridPoint(-150, -100),
                new GridPoint(50, -120),
                new GridPoint(150, 10),
                new GridPoint(60, 120),
                new GridPoint(-120, 60));
        GuiRenderState state = new GuiRenderState();
        GeometryScreenRenderer.draw(
                state, new Matrix3x2f(), frame, new PolygonGeometry(points), 400, 300, 0x4059E3CE, 0xFF59E3CE, 2);
        require(elements(state).size() == 1, "Fill and outline are not one draw in the native GUI queue");
        BufferedImage preview = raster(state);
        require(preview.getRGB(195, 140) >>> 24 == 64, "Polygon interior is invisible or has incorrect opacity");
        require(preview.getRGB(380, 280) == 0, "Polygon paints outside its boundary");
        require(preview.getRGB(150, 40) >>> 24 > 200, "Polygon edge is invisible");
        List<Vertex> edge = capture(elements(state).get(0)).vertices;
        require(
                edge.stream().anyMatch(v -> v.color() == 0xFF59E3CE)
                        && edge.stream().anyMatch(v -> v.color() == 0x0059E3CE),
                "Outline has no solid core or no fringe fading out for smooth diagonal sides");
        List<GridPoint> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        GuiRenderState reverse = new GuiRenderState();
        GeometryScreenRenderer.draw(
                reverse, new Matrix3x2f(), frame, new PolygonGeometry(reversed), 400, 300, 0x4059E3CE, 0xFF59E3CE, 2);
        require(
                raster(reverse).getRGB(195, 140) == preview.getRGB(195, 140),
                "Reversed polygon winding loses its fill");
        GuiRenderState concave = new GuiRenderState();
        var notch = new PolygonGeometry(List.of(
                new GridPoint(-150, -100),
                new GridPoint(150, -100),
                new GridPoint(150, 100),
                new GridPoint(0, 0),
                new GridPoint(-150, 100)));
        GeometryScreenRenderer.draw(concave, new Matrix3x2f(), frame, notch, 400, 300, 0x4059E3CE, 0xFF59E3CE, 2);
        BufferedImage concaveImage = raster(concave);
        require(
                concaveImage.getRGB(100, 100) >>> 24 == 64 && concaveImage.getRGB(200, 240) == 0,
                "Concave polygon fills its notch or loses its interior");
        GuiRenderState transformed = new GuiRenderState();
        Matrix3x2f pose = new Matrix3x2f().translate(12, 18).scale(0.75f);
        GeometryScreenRenderer.draw(
                transformed, pose, frame, new PolygonGeometry(points), 400, 300, 0x4059E3CE, 0xFF59E3CE, 2);
        var handle = new ColoredRectangleRenderState(
                RenderPipelines.GUI, TextureSetup.noTexture(), pose, 50, 50, 51, 51, -1, -1, null);
        Vertex handleOrigin = capture(handle).vertices.get(0);
        require(
                elements(transformed).stream()
                        .flatMap(e -> capture(e).vertices.stream())
                        .anyMatch(v -> Math.abs(v.x() - handleOrigin.x()) < 0.001
                                && Math.abs(v.y() - handleOrigin.y()) < 0.001),
                "Polygon no longer aligns with the same transformed GUI handle");
        GuiRenderState covering = new GuiRenderState();
        GeometryScreenRenderer.draw(
                covering,
                new Matrix3x2f(),
                frame,
                new RectangleGeometry(new SelectionBounds(-1000, -1000, 1000, 1000)),
                400,
                300,
                0x4059E3CE,
                -1,
                2);
        require(raster(covering).getRGB(195, 140) >>> 24 == 64, "Clipping removes a polygon enclosing the viewport");
        GuiRenderState offscreen = new GuiRenderState();
        GeometryScreenRenderer.draw(
                offscreen,
                new Matrix3x2f(),
                frame,
                new RectangleGeometry(new SelectionBounds(1000, 1000, 1100, 1100)),
                400,
                300,
                0x4059E3CE,
                -1,
                2);
        require(elements(offscreen).isEmpty(), "Offscreen polygon produces a GUI draw");
        GuiRenderState lines = new GuiRenderState();
        GeometryScreenRenderer.drawPolyline(
                lines,
                new Matrix3x2f(),
                frame,
                List.of(new GridPoint(-150, -100), new GridPoint(50, -100), new GridPoint(50, 100)),
                new GridPoint(150, 100),
                400,
                300,
                0xFF59E3CE);
        BufferedImage lineImage = raster(lines);
        require(
                lineImage.getRGB(120, 50) >>> 24 > 0
                        && lineImage.getRGB(250, 180) >>> 24 > 0
                        && lineImage.getRGB(300, 250) >>> 24 > 0,
                "Draft edges or rubber-band segment are invisible");
        for (double zoom : new double[] {0.03125, 0.0625, 0.13, 0.5, 1, 3.25}) {
            for (double guiScale : new double[] {1, 2, 3, 4}) {
                for (double camera : new double[] {-29_990_000.25, -17.25, 250.75, 29_990_000.75}) {
                    var fractional = new MapBridge.Frame(null, camera, camera, zoom, guiScale, 1601, 1201);
                    int origin = (int) Math.floor(camera);
                    var rectangle = new RectangleGeometry(new SelectionBounds(origin, origin, origin + 5, origin + 8));
                    GuiRenderState fractionalState = new GuiRenderState();
                    GeometryScreenRenderer.draw(
                            fractionalState, new Matrix3x2f(), fractional, rectangle, 1602, 1202, 0x4059E3CE, -1, 1);
                    // One draw: the fill's quad, then the outline.
                    var drawn = capture(elements(fractionalState).get(0)).vertices;
                    var fillVertices = drawn.subList(0, 4);
                    double expectedX = fractional.screenX(origin), expectedY = fractional.screenY(origin);
                    require(
                            fillVertices.stream()
                                    .anyMatch(v -> Math.abs(v.x() - expectedX) < 0.0001
                                            && Math.abs(v.y() - expectedY) < 0.0001),
                            "Rectangle corner rounded at fractional zoom or large world coordinates");
                    var edgeVertices = drawn.subList(4, drawn.size());
                    require(
                            Math.abs((edgeVertices.get(0).y()
                                                            + edgeVertices
                                                                    .get(1)
                                                                    .y())
                                                    / 2.0
                                            - expectedY)
                                    < 0.0001,
                            "Rectangle outline shifted relative to its fill");
                }
            }
        }
        state.nextStratum();
        state.submitGuiElement(new ColoredRectangleRenderState(
                RenderPipelines.GUI,
                TextureSetup.noTexture(),
                new Matrix3x2f(),
                180,
                130,
                220,
                170,
                0xFF101922,
                0xFF101922,
                null));
        require(raster(state).getRGB(195, 140) == 0xFF101922, "Polygon covers a later native menu or handle");
        if (args.length == 1)
            javax.imageio.ImageIO.write(
                    preview, "png", java.nio.file.Path.of(args[0]).toFile());
        System.out.println(
                "PASS: native GUI polygon fill/outline, depth regression, winding, concavity, transforms, clipping, draft edges, and UI layering");
    }
}
