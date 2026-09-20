package dev.mineagent.runtime.neoforge.mixin.client;
import dev.mineagent.runtime.neoforge.client.webui.McefTextureRetirement;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class McefRetirementShutdownMixin {
    @Inject(method="close",at=@At("HEAD"))
    private void mineagent$drainRetiredTextures(CallbackInfo callback){dev.mineagent.runtime.neoforge.client.webui.McefPaintBoundary.shutdown();McefTextureRetirement.shutdown();}
}
