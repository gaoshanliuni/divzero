package dev.mineagent.runtime.neoforge.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

/** Quote native profile names when command syntax needs it; never publish internal aliases. */
@Mixin(ClientSuggestionProvider.class)
public abstract class AgentCommandSuggestionsMixin {
    @Inject(method="getOnlinePlayerNames",at=@At("RETURN"),cancellable=true)
    private void divzero$names(CallbackInfoReturnable<Collection<String>> cir){
        cir.setReturnValue(cir.getReturnValue().stream().map(com.mojang.brigadier.arguments.StringArgumentType::escapeIfRequired).toList());
    }
}
