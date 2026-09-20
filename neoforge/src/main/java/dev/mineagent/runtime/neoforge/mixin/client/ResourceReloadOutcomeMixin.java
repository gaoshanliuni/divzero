package dev.mineagent.runtime.neoforge.mixin.client;
import dev.mineagent.runtime.neoforge.client.webui.ClientResourcePacks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class ResourceReloadOutcomeMixin {
    @Inject(method="<init>",at=@At("RETURN")) private void mineagent$ready(CallbackInfo ci){ClientResourcePacks.recoveryHookReady();}
    @Inject(method={"rollbackResourcePacks","clearResourcePacksOnError"},at=@At("HEAD")) private void mineagent$failed(CallbackInfo ci){ClientResourcePacks.recoveryStarted();}
}
