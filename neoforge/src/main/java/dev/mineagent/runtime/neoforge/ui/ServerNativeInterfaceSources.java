package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.mineagent.runtime.core.ui.dynamic.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.ScoreHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Live source deltas use their own data revision; they do not churn editable UI structure versions. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerNativeInterfaceSources {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Values(Map<String,JsonNode> data,Map<String,String> errors){}
    private record Key(UUID owner,UUID agent,String id){}
    private static final Map<MinecraftServer,Map<Key,Watch>> LIVE=new IdentityHashMap<>();
    private static final class Watch {
        final Key key;final ServerPlayer player;final Object level;final UUID world;final long permission,revision;final InterfaceDefinition definition;Map<String,JsonNode> sent=Map.of();Map<String,String> errors=Map.of();boolean busy;long next;
        Watch(ServerPlayer p,UUID agent,InterfaceDefinition definition,long revision){this.player=p;this.level=p.level();this.key=new Key(p.getUUID(),agent,definition.id());this.world=MineAgentRuntimeServices.worldId(p.level().getServer());this.permission=permission(p);this.definition=definition;this.revision=revision;}
    }
    private static long permission(ServerPlayer p){return MineAgentRuntimeServices.permissions(p.level().getServer()).actionRevision(p.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE);}
    private static boolean current(Watch w){var server=w.player.level().getServer();return w.player.level()==w.level&&server.getPlayerList().getPlayer(w.key.owner)==w.player&&w.world.equals(MineAgentRuntimeServices.worldId(server))&&permission(w.player)==w.permission&&ServerTaskStart.allowed(w.player,w.key.agent);}
    public static void register(ServerPlayer p,UUID agent,InterfaceDefinition definition,long revision){var all=LIVE.computeIfAbsent(p.level().getServer(),s->new LinkedHashMap<>());var key=new Key(p.getUUID(),agent,definition.id());if(definition.sources().isEmpty()){all.remove(key);return;}all.put(key,new Watch(p,agent,definition,revision));}
    public static void unregister(ServerPlayer player,UUID agent,String id){var all=LIVE.get(player.level().getServer());if(all!=null)all.remove(new Key(player.getUUID(),agent,id));}
    public static Values read(ServerPlayer p,UUID agent,InterfaceDefinition definition){
        var values=new LinkedHashMap<String,JsonNode>();var errors=new LinkedHashMap<String,String>();var server=p.level().getServer();if(!server.isSameThread())throw new IllegalStateException("NATIVE_SOURCE_SERVER_THREAD");int bytes=0;
        for(var entry:definition.sources().entrySet())try{
            var source=entry.getValue();JsonNode value;
            switch(source.kind()){
                case "score"->{if(!MineAgentRuntimeServices.permissions(server).allowed(p.getUUID(),p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_SCOREBOARD))throw new IllegalArgumentException("NATIVE_SOURCE_SCORE_PERMISSION");var objective=server.getScoreboard().getObjective(source.objective());if(objective==null)throw new IllegalArgumentException("NATIVE_SOURCE_OBJECTIVE_MISSING");String holder=source.holder();if(holder.equals("$viewer"))holder=p.getScoreboardName();else if(holder.equals("$agent"))holder=MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow(()->new IllegalArgumentException("NATIVE_SOURCE_AGENT_OFFLINE")).getScoreboardName();var score=server.getScoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(holder),objective);value=IntNode.valueOf(score==null?0:score.value());}
                case "agent"->{var body=MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow(()->new IllegalArgumentException("NATIVE_SOURCE_AGENT_OFFLINE"));value=switch(source.field()){case "health"->FloatNode.valueOf(body.getHealth());case "max_health"->FloatNode.valueOf(body.getMaxHealth());case "food"->IntNode.valueOf(body.getFoodData().getFoodLevel());case "name"->TextNode.valueOf(body.getDisplayName().getString());default->throw new IllegalArgumentException("NATIVE_SOURCE_FIELD");};}
                case "player"->{value=switch(source.field()){case "health"->FloatNode.valueOf(p.getHealth());case "max_health"->FloatNode.valueOf(p.getMaxHealth());case "food"->IntNode.valueOf(p.getFoodData().getFoodLevel());case "name"->TextNode.valueOf(p.getDisplayName().getString());case "x"->DoubleNode.valueOf(p.getX());case "y"->DoubleNode.valueOf(p.getY());case "z"->DoubleNode.valueOf(p.getZ());case "yaw"->FloatNode.valueOf(p.getYRot());case "pitch"->FloatNode.valueOf(p.getXRot());case "experience"->IntNode.valueOf(p.experienceLevel);default->throw new IllegalArgumentException("NATIVE_SOURCE_FIELD");};}
                case "world"->{value=switch(source.field()){case "game_time"->LongNode.valueOf(p.level().getGameTime());case "dimension"->TextNode.valueOf(p.level().dimension().identifier().toString());case "raining"->BooleanNode.valueOf(p.level().isRaining());case "thundering"->BooleanNode.valueOf(p.level().isThundering());default->throw new IllegalArgumentException("NATIVE_SOURCE_FIELD");};}
                case "block"->value=block(p,source);
                case "blocks"->value=blocks(p,source);
                case "task"->{var task=MineAgentRuntimeServices.tasks(server).get(UUID.fromString(source.taskId())).filter(t->t.ownerPlayerId().equals(p.getUUID())&&t.agentId().equals(agent)&&t.worldId().equals(MineAgentRuntimeServices.worldId(server))).orElseThrow(()->new IllegalArgumentException("NATIVE_SOURCE_TASK_SCOPE"));value=switch(source.field()){case "status"->TextNode.valueOf(task.status().name());case "title"->TextNode.valueOf(task.title());case "revision"->LongNode.valueOf(task.revision());case "total_steps"->IntNode.valueOf(task.steps().size());case "completed_steps"->LongNode.valueOf(task.steps().stream().filter(s->s.status()==dev.mineagent.runtime.api.task.TaskNodeStatus.COMPLETED).count());default->throw new IllegalArgumentException("NATIVE_SOURCE_FIELD");};}
                default->throw new IllegalArgumentException("NATIVE_SOURCE_KIND");
            }int size=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes+size>48000)throw new IllegalArgumentException("NATIVE_SOURCE_DATA_BUDGET");bytes+=size;values.put(entry.getKey(),value);
        }catch(Exception failure){errors.put(entry.getKey(),Objects.toString(failure.getMessage(),"NATIVE_SOURCE_UNAVAILABLE"));}
        return new Values(Map.copyOf(values),Map.copyOf(errors));
    }
    private static JsonNode block(ServerPlayer player,InterfaceSources.Source source){
        var level=player.level();var delta=source.relative();var pos=player.blockPosition().offset(delta.get(0),delta.get(1),delta.get(2));boolean loaded=level.isInWorldBounds(pos)&&level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4);
        if(source.field().equals("loaded"))return BooleanNode.valueOf(loaded);if(!loaded)throw new IllegalArgumentException("NATIVE_SOURCE_BLOCK_UNLOADED");var state=level.getBlockState(pos);
        return switch(source.field()){case "id"->TextNode.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());case "state"->TextNode.valueOf(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));case "solid"->BooleanNode.valueOf(!state.getCollisionShape(level,pos).isEmpty());case "fluid"->TextNode.valueOf(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());case "position"->JSON.valueToTree(List.of(pos.getX(),pos.getY(),pos.getZ()));default->throw new IllegalArgumentException("NATIVE_SOURCE_FIELD");};
    }
    private static JsonNode blocks(ServerPlayer player,InterfaceSources.Source source){
        var center=player.blockPosition();int radius=source.radius();var region=new dev.mineagent.runtime.core.conversation.BlockObservationRegion(center.getX()-radius,center.getY()-radius,center.getZ()-radius,center.getX()+radius,center.getY()+radius,center.getZ()+radius,0);var counts=new TreeMap<String,Integer>();int unknown=0;long end=Math.min(region.volume(),source.offset()+4096);var level=player.level();
        for(long i=source.offset();i<end;i++){var point=region.at(i);var pos=new net.minecraft.core.BlockPos(point[0],point[1],point[2]);if(!level.isInWorldBounds(pos)||!level.getChunkSource().hasChunk(point[0]>>4,point[2]>>4)){unknown++;continue;}counts.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(),1,Integer::sum);}
        return JSON.valueToTree(Map.of("center",List.of(center.getX(),center.getY(),center.getZ()),"dimension",level.dimension().identifier().toString(),"radius",radius,"offset",source.offset(),"nextOffset",end<region.volume()?end:-1,"totalCells",region.volume(),"counts",counts,"unknown",unknown,"gameTick",level.getGameTime()));
    }
    private static ObjectNode message(Watch w,String kind){return JSON.createObjectNode().put("kind",kind).put("world",w.world.toString()).put("owner",w.key.owner.toString()).put("agent",w.key.agent.toString()).put("id",w.key.id).put("dimension",w.player.level().dimension().identifier().toString()).put("expectedRevision",w.revision);}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var server=event.getServer();var all=LIVE.get(server);if(all==null)return;long now=server.overworld().getGameTime();
        int blockBudget=8192;for(var watch:all.values().stream().sorted(Comparator.comparingLong(w->w.next)).toList()){
            if(!current(watch)){all.remove(watch.key);if(server.getPlayerList().getPlayer(watch.key.owner)==watch.player)PacketDistributor.sendToPlayer(watch.player,new UiPayloads.Event(UUID.randomUUID(),"nativeInterface",message(watch,"revoke").toString()));continue;}
            if(watch.busy||now<watch.next)continue;int cost=InterfaceSources.blockReadCost(watch.definition);if(cost>blockBudget)continue;blockBudget-=cost;watch.next=now+10;var values=read(watch.player,watch.key.agent,watch.definition);var patch=new LinkedHashMap<String,JsonNode>();values.data.forEach((key,value)->{if(!value.equals(watch.sent.get(key)))patch.put(key,value);});if(patch.isEmpty()&&values.errors.equals(watch.errors))continue;
            watch.busy=true;var request=message(watch,"feed");request.set("data",JSON.valueToTree(patch));request.set("sourceErrors",JSON.valueToTree(values.errors));
            ServerNativeInterfaces.request(watch.player,watch.key.agent,request,()->current(watch)).whenComplete((receipt,error)->server.execute(()->{if(all.get(watch.key)!=watch)return;watch.busy=false;if(error==null&&receipt.path("status").asText().equals("APPLIED")){watch.sent=values.data;watch.errors=values.errors;}else watch.next=server.overworld().getGameTime()+100;}));
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event){LIVE.remove(event.getServer());}
    private ServerNativeInterfaceSources(){}
}
