package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.renderer.entity.EntityRenderer;import net.minecraft.client.renderer.entity.state.EntityRenderState;import net.minecraft.world.entity.Entity;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityRenderer.class)
public abstract class EntityRenderContextMixin {
 @Inject(method="extractRenderState",at=@At("TAIL")) private void mineagent$context(Entity entity,EntityRenderState state,float partial,CallbackInfo ci){state.setRenderData(dev.mineagent.runtime.neoforge.client.objects.EntityVisualClient.KEY,dev.mineagent.runtime.neoforge.client.objects.EntityVisualClient.metadata(entity,state.ageInTicks));}
}
