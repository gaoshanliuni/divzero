package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.core.ui.dynamic.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.WorldIdentityRuntime;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;

/** Restores only visible committed passive HUDs after joining or changing dimension. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerNativeInterfaceRestore {
    private record Context(ServerPlayer player,Object level){}
    private static final Map<MinecraftServer,Map<UUID,Context>> SEEN=new IdentityHashMap<>();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    public static void ready(ServerPlayer player){
        var server=player.level().getServer();if(!WorldIdentityRuntime.ready(server))return;
        if(!MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),false,dev.mineagent.runtime.api.permission.PermissionAction.CHAT))return;
        var seen=SEEN.computeIfAbsent(server,s->new HashMap<>());seen.entrySet().removeIf(e->server.getPlayerList().getPlayer(e.getKey())!=e.getValue().player);
        {
            if(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)return;var context=new Context(player,player.level());if(context.equals(seen.get(player.getUUID())))return;seen.put(player.getUUID(),context);
            UUID world=MineAgentRuntimeServices.worldId(server);var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
            CompletableFuture.supplyAsync(()->{try(var store=new NativeUiStore(db)){return store.owned(world,player.getUUID());}catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((records,error)->server.execute(()->{
                if(error!=null){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Native HUD restoration could not read saved definitions: {}",error.getClass().getSimpleName());return;}
                if(server.getPlayerList().getPlayer(player.getUUID())!=player||player.level()!=context.level||!world.equals(MineAgentRuntimeServices.worldId(server)))return;
                for(var record:records)if(record.saved().visible()&&record.saved().dimension().equals(player.level().dimension().identifier().toString())&&ServerTaskStart.allowed(player,record.agent()))try{
                    if(InterfaceDefinition.parse(record.saved().source()).surface()==InterfaceDefinition.Surface.HUD)ServerNativeInterfaces.restoreHud(player,record.agent(),record.id());
                }catch(Exception invalid){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Native HUD restoration rejected an invalid definition");}
            }));
        }
    }
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p){var seen=SEEN.get(p.level().getServer());if(seen!=null)seen.remove(p.getUUID());}}
    public static void invalidate(ServerPlayer player){var seen=SEEN.get(player.level().getServer());if(seen!=null)seen.remove(player.getUUID());}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event){SEEN.remove(event.getServer());}
    private ServerNativeInterfaceRestore(){}
}
