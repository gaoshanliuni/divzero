package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativePlayerEventSource {
    @SubscribeEvent public static void joined(PlayerEvent.PlayerLoggedInEvent event){publish("PLAYER_JOIN",event);}
    @SubscribeEvent public static void left(PlayerEvent.PlayerLoggedOutEvent event){publish("PLAYER_LEAVE",event);}
    private static void publish(String kind,PlayerEvent event){if(event.getEntity() instanceof ServerPlayer player&&!(player instanceof MineAgentPlayer)){var runtime=MineAgentRuntimeServices.eventsIfPresent(player.level().getServer());if(runtime!=null)runtime.playerEvent(kind,player);}}
}
