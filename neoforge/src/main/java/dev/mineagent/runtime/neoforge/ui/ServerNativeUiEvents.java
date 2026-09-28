package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.mineagent.runtime.core.ui.dynamic.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Declared native callbacks execute through the same game-tool permission and journal layer as chat. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class ServerNativeUiEvents {
    private static final ObjectMapper JSON=new ObjectMapper();private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private record Flight(ServerPlayer player,UUID agent,String view,long revision,AtomicBoolean live){}
    private static final Map<UUID,Flight> LIVE=new ConcurrentHashMap<>();
    private record View(net.minecraft.server.MinecraftServer server,UUID owner,UUID agent,String id){}
    private static final Map<View,Long> HEADS=new ConcurrentHashMap<>();
    private static <T> CompletableFuture<T> io(Callable<T> action){return CompletableFuture.supplyAsync(()->{try{return action.call();}catch(Exception e){throw new CompletionException(e);}},IO);}
    private static void require(boolean condition,String code){if(!condition)throw new IllegalArgumentException(code);}
    static void invalidate(ServerPlayer p,UUID agent,String view,long revision){long latest=HEADS.merge(new View(p.level().getServer(),p.getUUID(),agent,view),revision,Math::max);for(var flight:LIVE.values())if(flight.player==p&&flight.agent.equals(agent)&&flight.view.equals(view)&&flight.revision!=latest)flight.live.set(false);}
    private static boolean currentHead(ServerPlayer p,UUID agent,String id,long revision){return HEADS.getOrDefault(new View(p.level().getServer(),p.getUUID(),agent,id),revision)==revision;}
    private static boolean current(ServerPlayer p,UUID agent,Object level){return p.level()==level&&p.level().getServer().getPlayerList().getPlayer(p.getUUID())==p&&ServerTaskStart.allowed(p,agent);}
    public static void accept(ServerPlayer p,UiPayloads.Command packet){
        var server=p.level().getServer();var level=p.level();final UUID agent;final String id,node,event;final int actionIndex;final long revision;final JsonNode input;
        try{
            input=JSON.readTree(packet.json());require(input.isObject()&&packet.json().length()<=65536,"NATIVE_EVENT_SIZE");require(input.path("revision").isIntegralNumber()&&input.path("revision").canConvertToLong()&&input.path("actionIndex").isIntegralNumber()&&input.path("actionIndex").canConvertToInt(),"NATIVE_EVENT_VERSION");agent=UUID.fromString(input.path("agent").asText());id=input.path("id").asText();node=input.path("node").asText();event=input.path("event").asText();revision=input.path("revision").asLong(-1);actionIndex=input.path("actionIndex").asInt(-1);
            require(id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}")&&revision>0&&actionIndex>=0&&current(p,agent,level),"NATIVE_EVENT_SCOPE");
        }catch(Exception invalid){reply(p,packet.requestId(),Map.of("status","REJECTED","error",Objects.toString(invalid.getMessage(),"NATIVE_EVENT_INVALID")));return;}
        var scope=new NativeUiStore.Scope(MineAgentRuntimeServices.worldId(server),p.getUUID(),agent);var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        io(()->{try(var store=new NativeUiStore(db)){require(store.pending(scope,id).isEmpty(),"NATIVE_EVENT_CANDIDATE_PENDING");return store.get(scope,id).orElseThrow(()->new IllegalArgumentException("NATIVE_EVENT_VIEW_MISSING"));}}).whenComplete((saved,error)->server.execute(()->{
            try{
                if(error!=null)throw new IllegalArgumentException("NATIVE_EVENT_READ_FAILED");invalidate(p,agent,id,saved.revision());require(current(p,agent,level)&&currentHead(p,agent,id,revision)&&saved.revision()==revision&&saved.visible()&&saved.dimension().equals(level.dimension().identifier().toString()),"NATIVE_EVENT_STALE_VIEW");
                var definition=InterfaceDefinition.parse(saved.source());var target=definition.node(node).orElseThrow(()->new IllegalArgumentException("NATIVE_EVENT_NODE"));var actions=target.path("events").path(event);require(actions.isArray()&&actionIndex<actions.size(),"NATIVE_EVENT_ACTION");var action=actions.get(actionIndex);String name=action.path("action").asText();require(action.path("op").asText().equals("emit"),"NATIVE_EVENT_ACTION");var handler=definition.handlers().get(name);require(handler!=null,"NATIVE_EVENT_UNREGISTERED_ACTION");
                var data=new LinkedHashMap<String,JsonNode>(definition.data());require(input.path("data").isObject()&&input.path("data").size()<=InterfaceDefinition.MAX_NODES,"NATIVE_EVENT_DATA");for(var e:input.get("data").properties()){require(e.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,95}"),"NATIVE_EVENT_DATA_KEY");data.put(e.getKey(),e.getValue());}
                definition.sources().keySet().forEach(key->data.put(key,NullNode.instance));data.putAll(ServerNativeInterfaceSources.read(p,agent,definition).data());data.put("event",action.path("args").isMissingNode()?JSON.createObjectNode():action.get("args"));data.put("eventValue",input.path("eventValue"));require(definition.interactiveNode(node,data),"NATIVE_EVENT_DISABLED");
                JsonNode args=handler.resolve(data);String arguments=JSON.writeValueAsString(args);var lease=new Flight(p,agent,id,revision,new AtomicBoolean(true));
                io(()->{try(var store=new NativeUiEventStore(db)){return store.begin(scope,id,revision,packet.requestId(),node,name,handler.tool(),arguments);}}).whenComplete((claim,claimError)->server.execute(()->{
                    if(claimError!=null){reply(p,packet.requestId(),Map.of("status","REJECTED","error","NATIVE_EVENT_RESERVATION_FAILED"));return;}
                    if(!claim.dispatch()){reply(p,packet.requestId(),current(p,agent,level)&&currentHead(p,agent,id,revision)?receipt(claim.event(),handler.resultKey()):Map.of("status","REJECTED","error","NATIVE_EVENT_STALE_VIEW"));return;}
                    LIVE.put(claim.event().toolOperation(),lease);
                    CompletableFuture<Map<String,Object>> applied;
                    if(!current(p,agent,level)||!currentHead(p,agent,id,revision))applied=CompletableFuture.completedFuture(Map.of("status","REJECTED","error","NATIVE_EVENT_CONTEXT_CHANGED"));
                    else try{applied=ConversationAgentTools.execute(p,agent,claim.event().toolOperation(),handler.tool(),arguments,()->lease.live.get()&&currentHead(p,agent,id,revision)&&current(p,agent,level));}catch(Exception dispatchFailure){applied=CompletableFuture.completedFuture(Map.of("status","UNKNOWN","error","NATIVE_EVENT_DISPATCH_EXCEPTION"));}
                    applied.whenComplete((result,failure)->{
                        LIVE.remove(claim.event().toolOperation(),lease);var actual=failure==null&&result!=null?result:Map.<String,Object>of("status","UNKNOWN","error","NATIVE_EVENT_TOOL_OUTCOME_UNKNOWN");
                        io(()->{String json=JSON.writeValueAsString(actual);if(json.length()>16384){var bounded=new LinkedHashMap<String,Object>();actual.forEach((key,value)->{if(value instanceof Number||value instanceof Boolean||value instanceof String s&&s.length()<256)bounded.put(key,value);});bounded.put("detailsRequireToolInspection",true);bounded.put("toolOperation",claim.event().toolOperation());json=JSON.writeValueAsString(bounded);}try(var store=new NativeUiEventStore(db)){return store.complete(claim.event(),json);}}).whenComplete((completed,saveError)->server.execute(()->{if(saveError!=null)reply(p,packet.requestId(),Map.of("status","UNKNOWN","error","NATIVE_EVENT_RECEIPT_SAVE_FAILED","toolOperation",claim.event().toolOperation()));else reply(p,packet.requestId(),receipt(completed,handler.resultKey()));}));
                    });
                }));
            }catch(Exception invalid){reply(p,packet.requestId(),Map.of("status","REJECTED","error",Objects.toString(invalid.getMessage(),"NATIVE_EVENT_INVALID")));}
        }));
    }
    private static Map<String,Object> receipt(NativeUiEventStore.Event event,String resultKey){
        if(!event.state().equals("COMPLETED"))return Map.of("status","UNKNOWN","eventId",event.id(),"toolOperation",event.toolOperation(),"error","NATIVE_EVENT_PENDING_INSPECT_DO_NOT_REPLAY");
        try{var result=JSON.readTree(event.result());return Map.of("status",result.path("status").asText("OBSERVED"),"eventId",event.id(),"toolOperation",event.toolOperation(),"resultKey",resultKey,"result",result);}catch(Exception invalid){return Map.of("status","UNKNOWN","error","NATIVE_EVENT_RECEIPT_INVALID");}
    }
    private static void reply(ServerPlayer player,UUID id,Map<String,?> value){try{if(player.level().getServer().getPlayerList().getPlayer(player.getUUID())==player)PacketDistributor.sendToPlayer(player,new UiPayloads.Event(id,"nativeInterfaceEvent",JSON.writeValueAsString(value)));}catch(Exception ignored){/* Durable event outcome remains available through inspect_native_ui. */}}
    @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){HEADS.keySet().removeIf(key->key.server==event.getServer());for(var entry:LIVE.entrySet())if(entry.getValue().player.level().getServer()==event.getServer()){entry.getValue().live.set(false);LIVE.remove(entry.getKey(),entry.getValue());}}
    private ServerNativeUiEvents(){}
}
