package dev.exactselection;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

/** The render pipeline for area fills and outlines on the minimap: translucent, without depth test or culling. */
public final class AreaRenderPipelines {
    public static final RenderPipeline COLOR = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("xaero_exact_selection", "pipeline/area_color"))
            .withVertexShader("core/gui")
            .withFragmentShader("core/gui")
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

    private AreaRenderPipelines() {}
}
