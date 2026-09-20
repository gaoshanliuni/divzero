package dev.mineagent.runtime.neoforge.client.media;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mineagent.runtime.neoforge.content.MediaScreenBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class MediaScreenRenderer implements BlockEntityRenderer<MediaScreenBlockEntity, MediaScreenRenderState> {
    private static boolean smokeRendered;
    @Override
    public MediaScreenRenderState createRenderState() {
        return new MediaScreenRenderState();
    }

    @Override
    public void extractRenderState(
            MediaScreenBlockEntity blockEntity,
            MediaScreenRenderState state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress
    ) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        var level = blockEntity.getLevel();
        state.texture = level == null ? null : MineAgentMediaPlayback.texture(
                level.dimension().identifier().toString(), blockEntity.getBlockPos());
    }

    @Override
    public void submit(
            MediaScreenRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera
    ) {
        if (state.texture == null) {
            return;
        }
        if (Boolean.getBoolean("mineagent.multiplayerSmokeClient") && !smokeRendered) {
            smokeRendered = true;
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_MEDIA_SURFACE_RENDERED block={}", state.blockPos);
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(state.texture, false), (pose, buffer) -> {
            vertex(buffer, pose, state.lightCoords, 0.0625F, 0.0625F, 0, 1);
            vertex(buffer, pose, state.lightCoords, 0.9375F, 0.0625F, 1, 1);
            vertex(buffer, pose, state.lightCoords, 0.9375F, 0.9375F, 1, 0);
            vertex(buffer, pose, state.lightCoords, 0.0625F, 0.9375F, 0, 0);
        });
    }

    private static void vertex(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            int light,
            float x,
            float y,
            int u,
            int v
    ) {
        buffer.addVertex(pose, x, y, 1.002F).setColor(-1).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 0, 1);
    }
}
