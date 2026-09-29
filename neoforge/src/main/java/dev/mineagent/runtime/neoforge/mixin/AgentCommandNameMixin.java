package dev.mineagent.runtime.neoforge.mixin;

import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep authenticated profile identity; unique visible aliases are also native command targets. */
@Mixin(PlayerList.class)
public abstract class AgentCommandNameMixin {
    @Inject(method="getPlayerByName",at=@At("RETURN"),cancellable=true)
    private void divzero$alias(String name,CallbackInfoReturnable<ServerPlayer> cir){
        if(cir.getReturnValue()!=null)return;ServerPlayer match=null;
        for(var player:((PlayerList)(Object)this).getPlayers())if(player instanceof MineAgentPlayer&&player.getName().getString().equalsIgnoreCase(name)){
            if(match!=null)return;match=player;
        }
        if(match!=null)cir.setReturnValue(match);
    }
    @Inject(method="getPlayerNamesArray",at=@At("RETURN"),cancellable=true)
    private void divzero$suggest(CallbackInfoReturnable<String[]> cir){
        var names=new java.util.LinkedHashSet<String>(java.util.List.of(cir.getReturnValue()));
        var players=(PlayerList)(Object)this;
        for(var player:players.getPlayers())if(player instanceof MineAgentPlayer){String name=player.getName().getString();if(players.getPlayerByName(name)==player)names.add(com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(name));}
        cir.setReturnValue(names.toArray(String[]::new));
    }
}
