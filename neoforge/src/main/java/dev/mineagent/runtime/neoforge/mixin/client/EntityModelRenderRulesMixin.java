package dev.mineagent.runtime.neoforge.mixin.client;
import com.mojang.blaze3d.vertex.*;import net.minecraft.client.model.Model;import net.minecraft.client.renderer.*;import net.minecraft.client.renderer.rendertype.RenderType;import net.minecraft.client.renderer.feature.ModelFeatureRenderer;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
/** Wrap each real draw in try/finally. Shared model objects must not retain another entity's overrides. */
@Mixin(ModelFeatureRenderer.class)
public abstract class EntityModelRenderRulesMixin {
 @Redirect(method="renderModel",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
 private void mineagent$draw(Model<?> model,PoseStack poses,VertexConsumer buffer,int light,int overlay,int color,SubmitNodeStorage.ModelSubmit<?> submit,RenderType type,VertexConsumer outer,OutlineBufferSource outlines,MultiBufferSource.BufferSource breaking){dev.mineagent.runtime.neoforge.client.objects.EntityVisualClient.draw(model,submit.state(),poses,()->model.renderToBuffer(poses,buffer,light,overlay,color));}
}
