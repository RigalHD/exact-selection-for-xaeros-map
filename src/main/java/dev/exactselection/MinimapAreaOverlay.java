package dev.exactselection;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRendererHandler;
import xaero.lib.client.graphics.util.ImmediateRenderUtil;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

public final class MinimapAreaOverlay {
    private static final class StoreHolder {
        private static final SavedAreaStore STORE = new SavedAreaStore(FabricLoader.getInstance().getConfigDir());
    }
    private static boolean loadErrorLogged, drawErrorLogged;

    record Point(double x, double y) {}
    private record Vertex(Point point, int color) {}
    private record Projected(SavedAreaStore.Area area, List<Point> corners, List<List<Point>> triangles) {}

    private MinimapAreaOverlay() {}

    private static boolean unsupported;

    /** Draws saved areas on the minimap; an unsupported Minimap version turns the overlay off instead of crashing. */
    public static void render(Object handler, Object centerObject, Object dimensionObject, double zoom,
                              double ps, double pc, int halfViewW, int halfViewH, boolean circle) {
        if (unsupported) return;
        try {
            renderAreas(handler, centerObject, dimensionObject, zoom, ps, pc, halfViewW, halfViewH, circle);
        } catch (LinkageError error) {
            unsupported = true;
            LoggerFactory.getLogger("Exact Selection for Xaero's map")
                    .warn("This Xaero's Minimap version is not supported; areas are not drawn on the minimap", error);
        } catch (RuntimeException error) {
            // Skip this frame rather than break the minimap; the error may be passing, so the overlay stays on.
            if (!drawErrorLogged) {
                drawErrorLogged = true;
                LoggerFactory.getLogger("Exact Selection for Xaero's map")
                        .warn("Could not draw saved areas on the minimap", error);
            }
        }
    }

    private static void renderAreas(Object handler, Object centerObject, Object dimensionObject, double zoom,
                                    double ps, double pc, int halfViewW, int halfViewH, boolean circle) {
        if (!(centerObject instanceof Vec3 center) || !(dimensionObject instanceof ResourceKey<?> rawDimension)) return;
        WorldMapSession mapSession = WorldMapSession.getCurrentSession();
        if (mapSession == null) return;
        MapProcessor processor = mapSession.getMapProcessor();
        MapWorld mapWorld = processor.getMapWorld();
        if (mapWorld == null || !processor.isMapWorldUsable()) return;
        @SuppressWarnings("unchecked")
        ResourceKey<Level> dimension = (ResourceKey<Level>) rawDimension;
        MapDimension mapDimension = mapWorld.getDimension(dimension);
        if (mapDimension == null) return;
        SavedAreaStore store = StoreHolder.STORE;
        try {
            store.useContext(new MapBridge.Context(mapWorld, processor.getCurrentWorldId(), dimension,
                    mapDimension.getCurrentMultiworld()));
            loadErrorLogged = false;
        } catch (IOException e) {
            if (!loadErrorLogged) {
                LoggerFactory.getLogger("Exact Selection for Xaero's map").error("Could not load saved areas for the minimap", e);
                loadErrorLogged = true;
            }
            return;
        }
        SavedAreaStore.State state = store.state();
        if (!state.showOnMinimap() || halfViewW < 1 || halfViewH < 1 || zoom <= 0) return;
        MinimapElementGraphics graphics = ((MinimapElementRendererHandler) handler).getGuiGraphics();
        Matrix4f matrix = graphics.pose().last().pose();
        List<Point> viewport = viewport(halfViewW, halfViewH, circle);
        Map<String, Boolean> groupVisibility = new HashMap<>();
        for (SavedAreaStore.Group group : state.groups()) groupVisibility.put(group.id(), group.visible());
        List<Projected> projected = new ArrayList<>();
        for (SavedAreaStore.Area area : state.areas()) {
            if (!area.visible()) continue;
            if (!groupVisibility.getOrDefault(area.groupId(), true)) continue;
            AreaGeometry geometry = area.geometry();
            GeometryBounds bounds = geometry.bounds();
            List<Point> boundsCorners = List.of(
                    project(bounds.minX(), bounds.minZ(), center, ps, pc, zoom),
                    project(bounds.maxX(), bounds.minZ(), center, ps, pc, zoom),
                    project(bounds.maxX(), bounds.maxZ(), center, ps, pc, zoom),
                    project(bounds.minX(), bounds.maxZ(), center, ps, pc, zoom));
            if (outsideView(boundsCorners, halfViewW, halfViewH)) continue;
            List<Point> corners = geometry.vertices().stream()
                    .map(p -> project(p.x(), p.z(), center, ps, pc, zoom)).toList();
            List<List<Point>> triangles = geometry.triangulate().stream()
                    .map(triangle -> triangle.stream().map(p -> project(p.x(), p.z(), center, ps, pc, zoom)).toList())
                    .toList();
            projected.add(new Projected(area, corners, triangles));
        }
        drawFills(graphics, matrix, viewport, projected);
        drawOutlines(matrix, viewport, projected);
    }

    static Point project(double x, double z, Vec3 center, double ps, double pc, double zoom) {
        double dx = x - center.x;
        double dz = z - center.z;
        return new Point((ps * dx - pc * dz) * zoom, (pc * dx + ps * dz) * zoom);
    }

    static List<Point> viewport(int halfWidth, int halfHeight, boolean circle) {
        List<Point> points = new ArrayList<>();
        if (circle) {
            double radius = Math.max(0, halfWidth - 0.75);
            int segments = Math.max(64, (int) Math.ceil(Math.PI * Math.sqrt(radius)));
            for (int i = 0; i < segments; i++) {
                double angle = 2.0 * Math.PI * i / segments;
                points.add(new Point(Math.cos(angle) * radius, Math.sin(angle) * radius));
            }
        } else {
            points.add(new Point(-halfWidth, -halfHeight));
            points.add(new Point(halfWidth, -halfHeight));
            points.add(new Point(halfWidth, halfHeight));
            points.add(new Point(-halfWidth, halfHeight));
        }
        return points;
    }

    private static boolean outsideView(List<Point> points, int halfWidth, int halfHeight) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y()); maxY = Math.max(maxY, point.y());
        }
        return minX >= halfWidth || maxX <= -halfWidth || minY >= halfHeight || maxY <= -halfHeight;
    }

    private static void drawFills(MinimapElementGraphics graphics, Matrix4f matrix, List<Point> viewport,
                                  List<Projected> areas) {
        List<Vertex> vertices = new ArrayList<>();
        for (Projected projected : areas) {
            int alpha = projected.area().opacity() * 255 / 100;
            if (alpha == 0) continue;
            int color = (alpha << 24) | (projected.area().color() & 0xFFFFFF);
            for (List<Point> triangle : projected.triangles()) {
                List<Point> clipped = clipPolygon(triangle, viewport);
                if (clipped.size() < 3) continue;
                Point origin = clipped.get(0);
                for (int i = 1; i < clipped.size() - 1; i++) {
                    vertices.add(new Vertex(origin, color));
                    vertices.add(new Vertex(clipped.get(i), color));
                    vertices.add(new Vertex(clipped.get(i + 1), color));
                }
            }
        }
        draw(matrix, vertices);
    }

    private static void drawOutlines(Matrix4f matrix, List<Point> viewport, List<Projected> areas) {
        List<Vertex> vertices = new ArrayList<>();
        for (Projected projected : areas) {
            int color = 0xFF000000 | (projected.area().color() & 0xFFFFFF);
            List<Point> corners = projected.corners();
            for (int i = 0; i < corners.size(); i++) {
                Point[] clipped = clipSegment(corners.get(i), corners.get((i + 1) % corners.size()), viewport);
                if (clipped == null) continue;
                double dx = clipped[1].x() - clipped[0].x();
                double dy = clipped[1].y() - clipped[0].y();
                double length = Math.hypot(dx, dy);
                if (length < 0.01) continue;
                addQuad(vertices, clipped[0], clipped[1], -dy / length * 0.75, dx / length * 0.75, color);
            }
        }
        draw(matrix, vertices);
    }

    private static void addQuad(List<Vertex> vertices, Point a, Point b, double nx, double ny, int color) {
        Point a1 = new Point(a.x() + nx, a.y() + ny);
        Point a2 = new Point(a.x() - nx, a.y() - ny);
        Point b1 = new Point(b.x() + nx, b.y() + ny);
        Point b2 = new Point(b.x() - nx, b.y() - ny);
        for (Point point : List.of(a1, a2, b2, a1, b2, b1)) vertices.add(new Vertex(point, color));
    }

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
                if (currentInside != previousInside) output.add(intersection(previous, current, previousDistance, currentDistance));
                if (currentInside) output.add(current);
                previous = current;
                previousDistance = currentDistance;
            }
        }
        return output;
    }

    private static Point[] clipSegment(Point start, Point end, List<Point> clip) {
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

    private static Point intersection(Point start, Point end, double startDistance, double endDistance) {
        return interpolate(start, end, startDistance / (startDistance - endDistance));
    }

    private static Point interpolate(Point a, Point b, double t) {
        return new Point(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    private static double cross(Point a, Point b, Point p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    private static double signedArea(List<Point> polygon) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Point a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += a.x() * b.y() - b.x() * a.y();
        }
        return area / 2.0;
    }

    /**
     * Writes the vertices into Minecraft's shared buffer and draws them in one step: everything that can fail is
     * computed beforehand, so an error never leaves a half-written buffer for the next draw (the minimap's own).
     */
    private static void draw(Matrix4f matrix, List<Vertex> vertices) {
        if (vertices.isEmpty()) return;
        BufferBuilder builder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (Vertex vertex : vertices)
            builder.addVertex(matrix, (float) vertex.point().x(), (float) vertex.point().y(), 0).setColor(vertex.color());
        MeshData mesh = builder.build();
        if (mesh != null) ImmediateRenderUtil.drawImmediateMeshData(mesh, AreaRenderPipelines.COLOR,
                ImmediateRenderUtil.NO_TEXTURES);
    }
}
