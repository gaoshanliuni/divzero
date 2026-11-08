package dev.mineagent.runtime.neoforge.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRendererAdaptersMixin {
    @Redirect(method="submit",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private <S extends EntityRenderState> void mineagent$adapter(EntityRenderer<?,S> renderer,S state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){
        dev.mineagent.runtime.neoforge.client.objects.RendererAdapters.submit(renderer,state,poses,()->renderer.submit(state,poses,collector,camera));
    }
}
