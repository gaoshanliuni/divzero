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
            if(args.has("id")){String id=args.path("id").asText();var saved=store.get(scope,id);value.put("saved",saved.orElse(null));value.put("pending",store.pending(scope,id).orElse(null));value.put("failedCandidates",store.failed(scope,id));if(args.has("candidate_id"))value.put("candidate",store.failed(scope,id,UUID.fromString(args.path("candidate_id").asText())).orElseThrow());try(var events=new NativeUiEventStore(db)){value.put("events",events.list(scope,id));}}
            return value;
        }}).whenComplete((value,error)->server.execute(()->{
            if(error!=null){out.completeExceptionally(error);return;}if(!current(p,agent,level,guard)){out.complete(Map.of("status","REJECTED","error","NATIVE_UI_CONTEXT_CHANGED"));return;}
            var message=JSON.createObjectNode().put("kind","inspect").put("world",scope.world().toString()).put("owner",scope.owner().toString()).put("agent",agent.toString()).put("dimension",level.dimension().identifier().toString());
            if(args.has("id"))message.put("id",args.path("id").asText());
            request(p,agent,message,guard).whenComplete((client,failure)->server.execute(()->{
                value.put("client",failure==null?client:Map.of("status","UNAVAILABLE"));
                if(failure!=null||!args.has("id")||!(value.get("pending") instanceof NativeUiStore.Candidate candidate)){out.complete(value);return;}
                String id=args.path("id").asText();JsonNode observed=null;
                for(var view:client.path("views"))if(id.equals(view.path("id").asText())&&candidate.token().toString().equals(view.path("activationToken").asText())&&view.path("revision").asLong()==candidate.expectedRevision()+1)observed=view;
                if(observed==null||!current(p,agent,level,guard)||!candidate.dimension().equals(level.dimension().identifier().toString())){value.put("reconciliation","PENDING_CLIENT_EVIDENCE");out.complete(value);return;}
                var data=new LinkedHashMap<String,JsonNode>();observed.path("data").properties().forEach(e->data.put(e.getKey(),e.getValue()));boolean visible=observed.path("visible").asBoolean();
                io(()->{try(var store=new NativeUiStore(db)){return store.acknowledge(scope,id,candidate.token(),candidate.expectedRevision()+1,data,visible);}}).whenComplete((saved,saveError)->server.execute(()->{
                    if(saveError==null){ServerNativeUiEvents.invalidate(p,agent,id,saved.revision());if(saved.visible())ServerNativeInterfaceSources.register(p,agent,InterfaceDefinition.parse(saved.source()),saved.revision());else ServerNativeInterfaceSources.unregister(p,agent,id);value.put("saved",saved);value.put("pending",null);value.put("reconciliation","COMMITTED_FROM_CLIENT_EVIDENCE");}else value.put("reconciliation","PERSISTENCE_UNAVAILABLE");out.complete(value);
                }));
            }));
        }));return out;
    }
    public static CompletableFuture<Map<String,Object>> mutate(ServerPlayer p,UUID agent,String tool,JsonNode args,BooleanSupplier permit){
        var guard=lease(p,permit);var server=p.level().getServer();var level=p.level();var scope=scope(p,agent);var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        require(current(p,agent,level,guard),"NATIVE_UI_PERMISSION");String id=args.path("id").asText();require(id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"),"NATIVE_UI_ID");
        require(args.path("expected_revision").isIntegralNumber()&&args.path("expected_revision").canConvertToLong()&&args.path("expected_revision").longValue()>=0,"NATIVE_UI_REVISION");
        long expected=args.path("expected_revision").longValue();String lock=scope+":"+id;require(BUSY.add(lock),"NATIVE_UI_BUSY");var result=new CompletableFuture<Map<String,Object>>();
        result.whenComplete((v,e)->BUSY.remove(lock));
        io(()->{try(var store=new NativeUiStore(db)){if(store.pending(scope,id).isPresent())throw new IllegalStateException("NATIVE_UI_RECONCILE_REQUIRED: inspect_native_ui with this id");return store.get(scope,id).orElse(null);}}).whenComplete((old,readError)->server.execute(()->{
            if(readError!=null){result.completeExceptionally(readError);return;}
            try{
                require(current(p,agent,level,guard),"NATIVE_UI_CONTEXT_CHANGED");require((old==null?0:old.revision())==expected,"NATIVE_UI_STALE_REVISION");
                String source=tool.equals("set_native_ui")?args.path("source").asText():old==null?"":old.source();var definition=InterfaceDefinition.parse(source);require(definition.id().equals(id),"NATIVE_UI_ID_MISMATCH");
                require(source.length()<=65536,"NATIVE_UI_SOURCE_SIZE");
                var values=new LinkedHashMap<String,JsonNode>(tool.equals("set_native_ui")?definition.data():old.data());
                if(tool.equals("patch_native_ui_data")){require(args.path("data").isObject(),"NATIVE_UI_DATA");args.get("data").properties().forEach(e->values.put(e.getKey(),e.getValue().deepCopy()));}
                String kind=switch(tool){case "set_native_ui"->"replace";case "patch_native_ui_data"->"data";case "control_native_ui"->args.path("action").asText();default->throw new IllegalArgumentException("NATIVE_UI_TOOL");};
                require(Set.of("replace","data","show","hide","interact","release").contains(kind),"NATIVE_UI_ACTION");
                require(!kind.equals("interact")||definition.surface()!=InterfaceDefinition.Surface.ENTITY_HUD,"NATIVE_ENTITY_HUD_PASSIVE");
                if(old!=null&&!tool.equals("set_native_ui"))require(old.dimension().equals(level.dimension().identifier().toString()),"NATIVE_UI_DIMENSION_CHANGED");
                var message=JSON.createObjectNode().put("kind",kind).put("id",id).put("world",scope.world().toString()).put("owner",scope.owner().toString()).put("agent",agent.toString()).put("dimension",level.dimension().identifier().toString()).put("expectedRevision",expected).put("revision",expected+1).put("source",source);
                var sourceValues=ServerNativeInterfaceSources.read(p,agent,definition);values.putAll(sourceValues.data());message.set("sourceErrors",JSON.valueToTree(sourceValues.errors()));message.set("data",JSON.valueToTree(values));if(kind.equals("data")){var patch=(com.fasterxml.jackson.databind.node.ObjectNode)args.get("data").deepCopy();sourceValues.data().forEach(patch::set);message.set("patch",patch);}
                require(message.toString().length()<=120000,"NATIVE_UI_WIRE_BUDGET");
                io(()->{try(var store=new NativeUiStore(db)){return store.stage(scope,id,expected,level.dimension().identifier().toString(),source);}}).whenComplete((candidate,stageError)->server.execute(()->{
                if(stageError!=null){result.complete(Map.of("status","REJECTED","error","NATIVE_UI_CANDIDATE_PERSISTENCE_FAILED"));return;}
                if(!current(p,agent,level,guard)){io(()->{try(var store=new NativeUiStore(db)){store.discard(scope,id,candidate.token());return true;}}).whenComplete((discarded,e)->result.complete(Map.of("status","REJECTED","error","NATIVE_UI_CONTEXT_CHANGED")));return;}
                message.put("activationToken",candidate.token().toString());
                request(p,agent,message,guard).whenComplete((ack,error)->server.execute(()->{
                    if(error!=null){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_CLIENT_ACK_TIMEOUT","replayed",false));return;}
                    if(!ack.path("status").asText().equals("APPLIED")){
                        if(ack.path("status").asText().equals("UNKNOWN")){result.complete(Map.of("status","UNKNOWN","error",ack.path("error").asText("NATIVE_UI_UNKNOWN"),"revision",expected));return;}
                        io(()->{try(var store=new NativeUiStore(db)){var draft=store.failed(scope,id,candidate.token(),expected,source,ack.path("error").asText("NATIVE_UI_BUILD_FAILED"));store.discard(scope,id,candidate.token());return draft;}}).whenComplete((draft,e)->result.complete(Map.of("status",e==null?"REJECTED":"UNKNOWN","error",ack.path("error").asText("NATIVE_UI_BUILD_FAILED"),"revision",expected,"candidate_id",candidate.token(),"executionState",e==null?"CANDIDATE_ONLY":"UNKNOWN","runningVersionPreserved",e==null)));return;
                    }
                    if(!current(p,agent,level,guard)){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_CONTEXT_CHANGED"));return;}
                    try{
                        require(ack.path("revision").asLong(-1)==expected+1&&ack.path("data").isObject()&&candidate.token().toString().equals(ack.path("activationToken").asText()),"NATIVE_UI_INVALID_ACK");
                        ServerNativeUiEvents.invalidate(p,agent,id,expected+1);
                        if(Boolean.getBoolean("mineagent.nativeUiSmoke")&&Boolean.getBoolean("mineagent.nativeUiSmokeLoseCommit")){System.clearProperty("mineagent.nativeUiSmokeLoseCommit");result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_SMOKE_LOST_COMMIT","replayed",false));return;}
                        var activeData=new LinkedHashMap<String,JsonNode>();ack.get("data").properties().forEach(e->activeData.put(e.getKey(),e.getValue()));boolean visible=ack.path("visible").asBoolean(true);
                        io(()->{try(var store=new NativeUiStore(db)){return store.acknowledge(scope,id,candidate.token(),expected+1,activeData,visible);}}).whenComplete((saved,saveError)->server.execute(()->{
                            if(saveError!=null){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_PERSISTENCE_FAILED","replayed",false));return;}
                            if(saved.visible())ServerNativeInterfaceSources.register(p,agent,definition,saved.revision());else ServerNativeInterfaceSources.unregister(p,agent,id);
                            result.complete(Map.of("status","APPLIED","id",id,"revision",saved.revision(),"surface",definition.surface().name(),"clientActivated",true,"visible",visible,"restartRequired",false,"reloadRequired",false));
                        }));
                    }catch(Exception invalid){result.complete(Map.of("status","UNKNOWN","error","NATIVE_UI_INVALID_ACK"));}
                }));
                }));
            }catch(Exception invalid){
                String diagnostic=Objects.toString(invalid.getMessage(),"NATIVE_UI_FAILED");
                if(tool.equals("set_native_ui")&&args.path("source").isTextual()&&args.path("source").asText().length()<=65536){
                    io(()->{try(var store=new NativeUiStore(db)){return store.failed(scope,id,UUID.randomUUID(),expected,args.get("source").asText(),diagnostic);}}).whenComplete((draft,error)->{
                        if(error!=null)result.complete(Map.of("status","REJECTED","error",diagnostic,"executionState","NOT_STARTED","runningVersionPreserved",true));
                        else result.complete(Map.of("status","REJECTED","error",diagnostic,"candidate_id",draft.token(),"expected_revision",expected,"executionState","CANDIDATE_ONLY","runningVersionPreserved",true));
                    });
                }else result.complete(Map.of("status","REJECTED","error",diagnostic,"executionState","NOT_STARTED"));
            }
        }));return result;
    }
    public static CompletableFuture<Map<String,Object>> edit(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit){
        var server=p.level().getServer();var scope=scope(p,agent);var level=p.level();var guard=lease(p,permit);String id=args.path("id").asText();long expected=args.path("expected_revision").asLong(-1);var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        require(current(p,agent,level,guard),"NATIVE_UI_PERMISSION");
        return io(()->{try(var store=new NativeUiStore(db)){
            String source;if(args.has("candidate_id")){var draft=store.failed(scope,id,UUID.fromString(args.path("candidate_id").asText())).orElseThrow();require(draft.baseRevision()==expected,"NATIVE_UI_DRAFT_BASE_CHANGED");source=draft.source();}
            else {var active=store.get(scope,id).orElseThrow();require(active.revision()==expected,"NATIVE_UI_STALE_REVISION");source=active.source();}
            for(var patch:args.path("edits")){var result=dev.mineagent.runtime.scripting.opencode.OpenCodeRuntime.edit(source,patch.path("old_text").asText(),patch.path("new_text").asText(),patch.path("replace_all").asBoolean());require(result.accepted(),"NATIVE_UI_EDIT: "+result.error());source=result.source();}
            return source;
        }}).handle((source,error)->{
            if(error==null)return Map.of("source",source);Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();return Map.of("error",Objects.toString(cause.getMessage(),"NATIVE_UI_EDIT_PREPARATION_FAILED"));
        }).thenCompose(prepared->server.submit(()->{
            if(prepared.containsKey("error"))return CompletableFuture.completedFuture(Map.<String,Object>of("status","REJECTED","error",prepared.get("error"),"executionState","NOT_STARTED","runningVersionPreserved",true));
            if(!current(p,agent,level,guard))return CompletableFuture.completedFuture(Map.<String,Object>of("status","REJECTED","error","NATIVE_UI_CONTEXT_CHANGED","executionState","NOT_STARTED"));
            var mutation=JSON.createObjectNode().put("id",id).put("expected_revision",expected).put("source",prepared.get("source"));return mutate(p,agent,"set_native_ui",mutation,guard);
        }).thenCompose(java.util.function.Function.identity()));
    }
    static CompletableFuture<JsonNode> request(ServerPlayer p,UUID agent,JsonNode message,BooleanSupplier permit){
        UUID request=UUID.randomUUID();var future=new CompletableFuture<JsonNode>();var pending=new Pending(p,agent,p.level(),permit,future);PENDING.put(request,pending);
        future.orTimeout(15,TimeUnit.SECONDS).whenComplete((v,e)->PENDING.remove(request,pending));
        PacketDistributor.sendToPlayer(p,new UiPayloads.Event(request,"nativeInterface",message.toString()));return future;
    }
    public static void reply(ServerPlayer p,UiPayloads.Command packet){
        var pending=PENDING.get(packet.requestId());if(pending==null||pending.player!=p||!current(p,pending.agent,pending.level,pending.permit))return;
        try{JsonNode value=JSON.readTree(packet.json());require(value.isObject(),"NATIVE_UI_ACK");pending.future.complete(value);}catch(Exception invalid){pending.future.completeExceptionally(invalid);}
    }
    static void restoreHud(ServerPlayer player,UUID agent,String id){
        var server=player.level().getServer();var level=player.level();var scope=scope(player,agent);var guard=lease(player,()->true);String lock=scope+":"+id;if(!BUSY.add(lock))return;
        var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        io(()->{try(var store=new NativeUiStore(db)){return store.pending(scope,id).isPresent()?null:store.get(scope,id).orElse(null);}}).whenComplete((saved,error)->server.execute(()->{
            try{
                if(error!=null||saved==null||!saved.visible()||!saved.dimension().equals(level.dimension().identifier().toString())||!current(player,agent,level,guard)){BUSY.remove(lock);return;}
                var definition=InterfaceDefinition.parse(saved.source());if(definition.surface()==InterfaceDefinition.Surface.SCREEN){BUSY.remove(lock);return;}
                var data=new LinkedHashMap<String,JsonNode>(saved.data());var live=ServerNativeInterfaceSources.read(player,agent,definition);data.putAll(live.data());
                var message=JSON.createObjectNode().put("kind","restore").put("id",id).put("world",scope.world().toString()).put("owner",scope.owner().toString()).put("agent",agent.toString()).put("dimension",saved.dimension()).put("source",saved.source()).put("revision",saved.revision());message.set("data",JSON.valueToTree(data));message.set("sourceErrors",JSON.valueToTree(live.errors()));
                request(player,agent,message,guard).whenComplete((receipt,failure)->server.execute(()->{BUSY.remove(lock);if(failure==null&&current(player,agent,level,guard)&&receipt.path("status").asText().equals("APPLIED")&&receipt.path("revision").asLong()==saved.revision())ServerNativeInterfaceSources.register(player,agent,definition,saved.revision());}));
            }catch(Exception failure){BUSY.remove(lock);dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Native HUD restore failed: {}",failure.getClass().getSimpleName());}
        }));
    }
    private ServerNativeInterfaces(){}
}
