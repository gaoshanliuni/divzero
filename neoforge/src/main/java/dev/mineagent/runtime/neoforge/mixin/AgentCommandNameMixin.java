package dev.mineagent.runtime.neoforge.mixin;

import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Common native player-name completion; actual GameProfiles now carry the AI names. */
@Mixin(PlayerList.class)
public abstract class AgentCommandNameMixin {
    @Inject(method="getPlayerNamesArray",at=@At("RETURN"),cancellable=true)
    private void divzero$suggest(CallbackInfoReturnable<String[]> cir){
        cir.setReturnValue(java.util.Arrays.stream(cir.getReturnValue()).map(com.mojang.brigadier.arguments.StringArgumentType::escapeIfRequired).toArray(String[]::new));
    }
}
