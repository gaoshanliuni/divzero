package dev.mineagent.runtime.neoforge.scoreboard;
import dev.mineagent.runtime.api.scoreboard.WorldBoardFrame;
import dev.mineagent.runtime.core.scoreboard.ScoreAudienceContext;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.WorldBoardPayload;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
/** No browser and no globally broadcast display entity. Each real recipient gets only its authorized projection. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldBoardRuntime {
    private static final Map<MinecraftServer,WorldBoardRuntime> runtimes=new IdentityHashMap<>();
    private final dev.mineagent.runtime.core.scoreboard.ScoreboardReadGuard readGuard=new dev.mineagent.runtime.core.scoreboard.ScoreboardReadGuard();
    private final UUID instance=UUID.randomUUID();private long sequence,ticks;
    private record Sent(Object player,String dimension,List<WorldBoardFrame.Board> boards,long tick){}
    private final Map<UUID,Sent> sent=new HashMap<>();
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        var server=event.getServer();if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;var runtime=runtimes.computeIfAbsent(server,s->new WorldBoardRuntime());if(++runtime.ticks%10!=0)return;
        var players=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).toList();if(players.isEmpty())return;
        runtime.readGuard.run(runtime.ticks,()->runtime.synchronize(server,players),failure->{
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("MineAgent scoreboard observation paused; native scores/world unchanged. Retrying read-only sync after 100 ticks.",failure);
            runtime.sent.clear();
            for(var player:players)try{
                PacketDistributor.sendToPlayer(player,new WorldBoardPayload(new WorldBoardFrame(runtime.instance,MineAgentRuntimeServices.worldId(server),player.getUUID(),++runtime.sequence,player.level().dimension().identifier().toString(),List.of())));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MineAgent] 计分板读取暂不可用，已暂停显示同步；原计分板和存档未修改。"));
            }catch(RuntimeException notificationFailure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Unable to notify scoreboard observation failure",notificationFailure);}
        });
    }
    private void synchronize(MinecraftServer server,List<net.minecraft.server.level.ServerPlayer> players){
        var scores=MineAgentRuntimeServices.scoreboards(server);
        var queries=players.stream().map(player->{var team=player.getTeam();return new dev.mineagent.runtime.core.scoreboard.ScoreboardService.BoardViewer(new ScoreAudienceContext(player.getUUID(),team==null?Set.of():Set.of(team.getName()),Set.of(),Set.of()),player.level().dimension().identifier().toString(),player.getX(),player.getY(),player.getZ());}).toList();
        var batch=scores.worldBoardBatch(queries);sent.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
        for(var player:players){
            String dimension=player.level().dimension().identifier().toString();
            var boards=batch.get(player.getUUID());var prior=sent.get(player.getUUID());
            if(prior!=null&&prior.player()==player&&prior.dimension().equals(dimension)&&prior.boards().equals(boards)&&ticks-prior.tick()<40)continue;
            PacketDistributor.sendToPlayer(player,new WorldBoardPayload(new WorldBoardFrame(instance,MineAgentRuntimeServices.worldId(server),player.getUUID(),++sequence,dimension,boards)));
            sent.put(player.getUUID(),new Sent(player,dimension,boards,ticks));
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){runtimes.remove(e.getServer());}
}
