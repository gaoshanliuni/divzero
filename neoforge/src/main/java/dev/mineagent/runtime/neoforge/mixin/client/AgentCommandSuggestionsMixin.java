package dev.mineagent.runtime.neoforge.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

/** Vanilla entity-argument completion also lists the visible names of online AI bodies. */
@Mixin(ClientSuggestionProvider.class)
public abstract class AgentCommandSuggestionsMixin {
    @Inject(method="getOnlinePlayerNames",at=@At("RETURN"),cancellable=true)
    private void divzero$names(CallbackInfoReturnable<Collection<String>> cir){
        var connection=Minecraft.getInstance().getConnection();if(connection==null)return;
        var known=dev.mineagent.runtime.neoforge.client.chat.NativeAgentChat.names();
        var counts=new TreeMap<String,Integer>(String.CASE_INSENSITIVE_ORDER);
        for(var info:connection.getOnlinePlayers())if(info.getProfile().name().startsWith("MA_")&&info.getTabListDisplayName()!=null){
            String name=info.getTabListDisplayName().getString();if(known.contains(name))counts.merge(name,1,Integer::sum);
        }
        var names=new LinkedHashSet<>(cir.getReturnValue());
        counts.forEach((name,count)->{if(count==1)names.add(com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(name));});
        cir.setReturnValue(names);
    }
}
