package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.Minecraft;
import dev.mineagent.runtime.neoforge.client.webui.McefPaintBoundary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class McefFrameBoundaryMixin {
    @Inject(method="renderFrame",at=@At("HEAD")) private void mineagent$begin(boolean advance,CallbackInfo ci){McefPaintBoundary.beginFrame();}
    @Inject(method="renderFrame",at=@At("RETURN")) private void mineagent$end(boolean advance,CallbackInfo ci){McefPaintBoundary.endFrame();}
}
