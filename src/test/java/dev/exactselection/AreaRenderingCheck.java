package dev.exactselection;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

public final class AreaRenderingCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        require(RenderPipelines.GUI.isCull(), "Regression case changed: native GUI no longer culls reverse-facing triangles");
        require(!AreaRenderPipelines.COLOR.isCull(), "Area triangles are still subject to face culling");
        require(AreaRenderPipelines.COLOR.getVertexFormatMode() == VertexFormat.Mode.TRIANGLES, "Area pipeline has the wrong topology");
        require(AreaRenderPipelines.COLOR.getUniforms().equals(RenderPipelines.GUI.getUniforms()), "Area pipeline is missing GUI transform uniforms");
        require(AreaRenderPipelines.COLOR.getVertexShader().equals(RenderPipelines.GUI.getVertexShader()), "Area shader does not match the GUI vertex data");
        Vec3 center = new Vec3(-29999000.75, 64, 29999000.25);
        for (double angle : new double[]{0, Math.PI / 2, Math.PI, Math.PI * 1.5, 0.33, -1.27}) {
            for (double zoom : new double[]{0.125, 0.5, 1, 2, 8}) {
                for (double[] delta : new double[][]{{0, 0}, {11.25, -53.75}, {-75.5, 22.25}, {99.9, 88.8}}) {
                    PoseStack pose = new PoseStack();
                    double[] partial = new double[2];
                    double sin = Math.sin(angle), cos = Math.cos(angle);
                    MinimapElementOverMapRendererHandler.translatePosition(pose, 100000, 100000, 100000, 100000,
                            sin, cos, delta[0], delta[1], zoom, false, partial);
                    ScreenGeometry.Point point = MinimapAreaOverlay.project(center.x + delta[0], center.z + delta[1], center, sin, cos, zoom);
                    double expectedX = pose.last().pose().m30() + partial[0];
                    double expectedY = pose.last().pose().m31() + partial[1];
                    require(Math.abs(point.x() - expectedX) < 1.0E-6 && Math.abs(point.y() - expectedY) < 1.0E-6,
                            "Area projection diverges from native Xaero at angle=" + angle + " zoom=" + zoom);
                }
            }
        }
        List<ScreenGeometry.Point> viewport = MinimapAreaOverlay.viewport(50, 50, true);
        List<ScreenGeometry.Point> covering = List.of(new ScreenGeometry.Point(-100, -100),
                new ScreenGeometry.Point(100, -100), new ScreenGeometry.Point(100, 100), new ScreenGeometry.Point(-100, 100));
        List<ScreenGeometry.Point> clipped = ScreenGeometry.clipPolygon(covering, viewport);
        require(clipped.size() >= 32, "Clipping removed an area enclosing the whole minimap");
        for (var point : clipped) require(Math.hypot(point.x(), point.y()) <= 50.0, "Area fill escaped the circular minimap mask");
        System.out.println("PASS: non-culling area pipeline, native Xaero projection, and minimap clipping");
    }

}
