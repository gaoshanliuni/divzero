package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.content.WorldReopenBootstrap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** The server's world stem exists now; a later failed launch must not be cancelled as if no native world could have begun. */
@Mixin(MinecraftServer.class)
public abstract class ManagedWorldServerConstructedMixin {
    @Inject(method="<init>",at=@At("RETURN"))
    private void mineagent$worldStarted(CallbackInfo ci){WorldReopenBootstrap.serverConstructed((MinecraftServer)(Object)this);}
}
