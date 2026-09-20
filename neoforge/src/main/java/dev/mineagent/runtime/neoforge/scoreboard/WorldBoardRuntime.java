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
    private final UUID instance=UUID.randomUUID();private long sequence,ticks;
    private record Sent(Object player,String dimension,List<WorldBoardFrame.Board> boards,long tick){}
    private final Map<UUID,Sent> sent=new HashMap<>();
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        var server=event.getServer();if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;var runtime=runtimes.computeIfAbsent(server,s->new WorldBoardRuntime());if(++runtime.ticks%10!=0)return;
        var players=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).toList();if(players.isEmpty())return;
        var scores=MineAgentRuntimeServices.scoreboards(server);
        var queries=players.stream().map(player->{var team=player.getTeam();return new dev.mineagent.runtime.core.scoreboard.ScoreboardService.BoardViewer(new ScoreAudienceContext(player.getUUID(),team==null?Set.of():Set.of(team.getName()),Set.of(),Set.of()),player.level().dimension().identifier().toString(),player.getX(),player.getY(),player.getZ());}).toList();
        var batch=scores.worldBoardBatch(queries);runtime.sent.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
        for(var player:players){
            String dimension=player.level().dimension().identifier().toString();
            var boards=batch.get(player.getUUID());var prior=runtime.sent.get(player.getUUID());
            if(prior!=null&&prior.player()==player&&prior.dimension().equals(dimension)&&prior.boards().equals(boards)&&runtime.ticks-prior.tick()<40)continue;
            PacketDistributor.sendToPlayer(player,new WorldBoardPayload(new WorldBoardFrame(runtime.instance,MineAgentRuntimeServices.worldId(server),player.getUUID(),++runtime.sequence,dimension,boards)));
            runtime.sent.put(player.getUUID(),new Sent(player,dimension,boards,runtime.ticks));
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){runtimes.remove(e.getServer());}
}
