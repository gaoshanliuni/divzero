package dev.mineagent.runtime.neoforge.compile;

import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;

/** Isolated positive-fixture grants; production defaults are untouched and logout restores the prior set. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeApiSmokeServer {
    private static final Map<UUID,Set<PermissionAction>> PREVIOUS=new HashMap<>();
    public static volatile UUID agent;
    private NativeApiSmokeServer(){}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(!Boolean.getBoolean("mineagent.nativeApiSmoke")||!(event.getEntity() instanceof ServerPlayer player)||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)return;var permissions=MineAgentRuntimeServices.permissions(player.level().getServer());var prior=permissions.trustedActions(player.getUUID());PREVIOUS.put(player.getUUID(),prior);var grants=new HashSet<>(prior);grants.add(PermissionAction.RUN_CODE);grants.add(PermissionAction.MANAGE_PACKAGES);if(Boolean.getBoolean("mineagent.nativeApiCoderSmoke"))grants.add(PermissionAction.START_TASK);permissions.setTrustedActions(player.getUUID(),grants);if(Boolean.getBoolean("mineagent.nativeApiCoderSmoke"))agent=MineAgentRuntimeServices.bodies(player.level().getServer()).create("Native overlay Coder",player,dev.mineagent.runtime.api.agent.AgentMode.CREATOR).agentId();}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(!(event.getEntity() instanceof ServerPlayer player)||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)return;if(agent!=null)try{MineAgentRuntimeServices.bodies(player.level().getServer()).remove(agent,player.getUUID(),true);}catch(Exception ignored){}agent=null;var prior=PREVIOUS.remove(player.getUUID());if(prior!=null)MineAgentRuntimeServices.permissions(player.level().getServer()).setTrustedActions(player.getUUID(),prior);}
}
