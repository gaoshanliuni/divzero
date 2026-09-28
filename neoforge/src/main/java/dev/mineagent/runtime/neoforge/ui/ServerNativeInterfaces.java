package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.ui.dynamic.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Native interface RPCs are bound to the actual player connection, dimension and AI authority. */
public final class ServerNativeInterfaces {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private record Pending(ServerPlayer player,UUID agent,Object level,BooleanSupplier permit,CompletableFuture<JsonNode> future){}
    private static final ConcurrentMap<UUID,Pending> PENDING=new ConcurrentHashMap<>();
    private static final Set<String> BUSY=ConcurrentHashMap.newKeySet();
    private static NativeUiStore.Scope scope(ServerPlayer p,UUID agent){return new NativeUiStore.Scope(MineAgentRuntimeServices.worldId(p.level().getServer()),p.getUUID(),agent);}
    private static boolean current(ServerPlayer p,UUID agent,Object level,BooleanSupplier permit){return p.level()==level&&p.level().getServer().getPlayerList().getPlayer(p.getUUID())==p&&permit.getAsBoolean()&&ServerTaskStart.allowed(p,agent);}
    private static BooleanSupplier lease(ServerPlayer p,BooleanSupplier permit){
        var server=p.level().getServer();var world=MineAgentRuntimeServices.worldId(server);
        long permission=MineAgentRuntimeServices.permissions(server).actionRevision(p.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE);
        return ()->permit.getAsBoolean()&&world.equals(MineAgentRuntimeServices.worldId(server))&&permission==MineAgentRuntimeServices.permissions(server).actionRevision(p.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE);
    }
    private static <T> CompletableFuture<T> io(Callable<T> action){return CompletableFuture.supplyAsync(()->{try{return action.call();}catch(Exception e){throw new CompletionException(e);}},IO);}
    private static void require(boolean condition,String code){if(!condition)throw new IllegalArgumentException(code);}
    public static CompletableFuture<Map<String,Object>> inspect(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit){
        var guard=lease(p,permit);var scope=scope(p,agent);var server=p.level().getServer();var level=p.level();var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        require(current(p,agent,level,guard),"NATIVE_UI_PERMISSION");
        var out=new CompletableFuture<Map<String,Object>>();
        io(()->{try(var store=new NativeUiStore(db)){
            var value=new LinkedHashMap<String,Object>();value.put("contract",InterfaceDefinition.CONTRACT);value.put("views",store.list(scope));
            if(args.has("id")){String id=args.path("id").asText();var saved=store.get(scope,id);value.put("saved",saved.orElse(null));}
            return value;
        }}).whenComplete((value,error)->server.execute(()->{
            if(error!=null){out.completeExceptionally(error);return;}if(!current(p,agent,level,guard)){out.complete(Map.of("status","REJECTED","error","NATIVE_UI_CONTEXT_CHANGED"));return;}
            var message=JSON.createObjectNode().put("kind","inspect").put("world",scope.world().toString()).put("owner",scope.owner().toString()).put("agent",agent.toString()).put("dimension",level.dimension().identifier().toString());
            if(args.has("id"))message.put("id",args.path("id").asText());
            request(p,agent,message,guard).whenComplete((client,failure)->server.execute(()->{value.put("client",failure==null?client:Map.of("status","UNAVAILABLE"));out.complete(value);}));
        }));return out;
    }
    public static CompletableFuture<Map<String,Object>> mutate(ServerPlayer p,UUID agent,String tool,JsonNode args,BooleanSupplier permit){
        var guard=lease(p,permit);var server=p.level().getServer();var level=p.level();var scope=scope(p,agent);var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        require(current(p,agent,level,guard),"NATIVE_UI_PERMISSION");String id=args.path("id").asText();require(id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"),"NATIVE_UI_ID");
        require(args.path("expected_revision").isIntegralNumber()&&args.path("expected_revision").canConvertToLong()&&args.path("expected_revision").longValue()>=0,"NATIVE_UI_REVISION");
        long expected=args.path("expected_revision").longValue();String lock=scope+":"+id;require(BUSY.add(lock),"NATIVE_UI_BUSY");var result=new CompletableFuture<Map<String,Object>>();
        result.whenComplete((v,e)->BUSY.remove(lock));
        io(()->{try(var store=new NativeUiStore(db)){return store.get(scope,id).orElse(null);}}).whenComplete((old,readError)->server.execute(()->{
            if(readError!=null){result.completeExceptionally(readError);return;}
            try{
                require(current(p,agent,level,guard),"NATIVE_UI_CONTEXT_CHANGED");require((old==null?0:old.revision())==expected,"NATIVE_UI_STALE_REVISION");
                String source=tool.equals("set_native_ui")?args.path("source").asText():old==null?"":old.source();var definition=InterfaceDefinition.parse(source);require(definition.id().equals(id),"NATIVE_UI_ID_MISMATCH");
                require(source.length()<=65536,"NATIVE_UI_SOURCE_SIZE");
                var values=new LinkedHashMap<String,JsonNode>(tool.equals("set_native_ui")?definition.data():old.data());
                if(tool.equals("patch_native_ui_data")){require(args.path("data").isObject(),"NATIVE_UI_DATA");args.get("data").properties().forEach(e->values.put(e.getKey(),e.getValue().deepCopy()));}
                String kind=switch(tool){case "set_native_ui"->"replace";case "patch_native_ui_data"->"data";case "control_native_ui"->args.path("action").asText();default->throw new IllegalArgumentException("NATIVE_UI_TOOL");};
                require(Set.of("replace","data","show","hide","interact","release").contains(kind),"NATIVE_UI_ACTION");
                if(old!=null&&!tool.equals("set_native_ui"))require(old.dimension().equals(level.dimension().identifier().toString()),"NATIVE_UI_DIMENSION_CHANGED");
                var message=JSON.createObjectNode().put("kind",kind).put("id",id).put("world",scope.world().toString()).put("owner",scope.owner().toString()).put("agent",agent.toString()).put("dimension",level.dimension().identifier().toString()).put("expectedRevision",expected).put("revision",expected+1).put("source",source);
                message.set("data",JSON.valueToTree(values));
                request(p,agent,message,guard).whenComplete((ack,error)->server.execute(()->{
                    if(error!=null){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_CLIENT_ACK_TIMEOUT","replayed",false));return;}
                    if(!ack.path("status").asText().equals("APPLIED")){result.complete(Map.of("status",ack.path("status").asText().equals("UNKNOWN")?"UNKNOWN":"REJECTED","error",ack.path("error").asText("NATIVE_UI_BUILD_FAILED"),"revision",expected));return;}
                    if(!current(p,agent,level,guard)){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_CONTEXT_CHANGED"));return;}
                    try{
                        require(ack.path("revision").asLong(-1)==expected+1&&ack.path("data").isObject(),"NATIVE_UI_INVALID_ACK");
                        var activeData=new LinkedHashMap<String,JsonNode>();ack.get("data").properties().forEach(e->activeData.put(e.getKey(),e.getValue()));boolean visible=ack.path("visible").asBoolean(true);
                        io(()->{try(var store=new NativeUiStore(db)){return store.save(scope,id,expected,level.dimension().identifier().toString(),source,activeData,visible);}}).whenComplete((saved,saveError)->server.execute(()->{
                            if(saveError!=null){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_PERSISTENCE_FAILED","replayed",false));return;}
                            result.complete(Map.of("status","APPLIED","id",id,"revision",saved.revision(),"surface",definition.surface().name(),"clientActivated",true,"visible",visible,"restartRequired",false,"reloadRequired",false));
                        }));
                    }catch(Exception invalid){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_INVALID_ACK"));}
                }));
            }catch(Exception invalid){result.complete(Map.of("status","REJECTED","error",Objects.toString(invalid.getMessage(),"NATIVE_UI_FAILED")));}
        }));return result;
    }
    private static CompletableFuture<JsonNode> request(ServerPlayer p,UUID agent,JsonNode message,BooleanSupplier permit){
        UUID request=UUID.randomUUID();var future=new CompletableFuture<JsonNode>();var pending=new Pending(p,agent,p.level(),permit,future);PENDING.put(request,pending);
        future.orTimeout(15,TimeUnit.SECONDS).whenComplete((v,e)->PENDING.remove(request,pending));
        PacketDistributor.sendToPlayer(p,new UiPayloads.Event(request,"nativeInterface",message.toString()));return future;
    }
    public static void reply(ServerPlayer p,UiPayloads.Command packet){
        var pending=PENDING.get(packet.requestId());if(pending==null||pending.player!=p||!current(p,pending.agent,pending.level,pending.permit))return;
        try{JsonNode value=JSON.readTree(packet.json());require(value.isObject(),"NATIVE_UI_ACK");pending.future.complete(value);}catch(Exception invalid){pending.future.completeExceptionally(invalid);}
    }
    private ServerNativeInterfaces(){}
}
