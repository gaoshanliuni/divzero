package dev.mineagent.runtime.neoforge.mixin.client;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityPartsMixin {
    @Redirect(method="submit",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
    private <S> void mineagent$parts(SubmitNodeCollector collector,Model<? super S> model,S state,PoseStack poses,RenderType type,int light,int overlay,int color,UvMapping mapping,int outline){
        collector.submitModel(model,state,poses,type,light,overlay,color,mapping,outline);
        dev.mineagent.runtime.neoforge.client.objects.EntityPartModels.submit(collector,model,state,poses,type,light,overlay,color,outline,null);
    }
}
