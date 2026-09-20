package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.ui.WorldUiProtocol;
import dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.core.ui.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Physical instance UI grants and callbacks. No author ownership or viewer OP is lent to a webpage. */
public final class ServerWorldUiRuntime implements AutoCloseable {
    private final MinecraftServer server;private final UiSessionService sessions;private final ObjectMapper json=new ObjectMapper();
    private final WorldUiActionJournal actions;private final Map<String,View> views=new LinkedHashMap<>();private final Map<UUID,View> transfers=new HashMap<>();
    private final PackageTransferLeases leases=new PackageTransferLeases(java.time.Clock.systemUTC());
    private final java.util.concurrent.ExecutorService io=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"mineagent-world-ui-bundle");t.setDaemon(true);return t;});
    private boolean closed;private int callbackTick=-1,callbacks;
    private static final class View{final Launch launch;final ServerPlayer actor;Session session;boolean preparing,offered,pushListening;long pushListenRevision,pushPage=-1,pushControl=-1;long expires;View(Launch launch,ServerPlayer actor){this.launch=launch;this.actor=actor;expires=launch.expiresAt();}}
    private static final class AgentInteraction{final View source;final dev.mineagent.runtime.neoforge.body.MineAgentPlayer body;final dev.mineagent.runtime.api.task.ManagedTask task;Launch offered;String error;AgentInteraction(View source,dev.mineagent.runtime.neoforge.body.MineAgentPlayer body,dev.mineagent.runtime.api.task.ManagedTask task){this.source=source;this.body=body;this.task=task;}}
    private AgentInteraction agentInteraction;
    ServerWorldUiRuntime(MinecraftServer server,UiSessionService sessions){this.server=server;this.sessions=sessions;try{actions=WorldUiActionJournal.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server));}catch(Exception e){throw new IllegalStateException("WORLD_UI_STORE",e);}}
    public void issue(ServerPlayer viewer,UUID instance,UUID entity,String part,String entryId)throws Exception{
        requireThread();
        if(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer){
            var pending=agentInteraction;if(pending==null||pending.body!=viewer)return; // Clientless interaction alone cannot open a browser or damage the owning script.
            var original=pending.source.launch;var target=WorldContentRuntime.get(server).uiTarget(instance,entity,part,viewer);var entry=target.pack().entrypoints().get(entryId);
            if(!original.instanceId().equals(instance)||!original.entityId().equals(entity)||!original.part().equals(part)||entry==null||entry.side()!=RuntimeResourceSide.CLIENT||!original.entryPath().equals(entry.path())||!target.pack().canonicalSha256().equals(original.canonicalSha256())){pending.error="WORLD_UI_AGENT_ENTRY_CHANGED";return;}
            if(pending.offered==null)pending.offered=original.forAgent(pending.task.agentId(),pending.task.taskId(),pending.task.intentRevision(),Math.min(pending.source.expires,System.currentTimeMillis()+60_000));return;
        }
        var target=WorldContentRuntime.get(server).uiTarget(instance,entity,part,viewer);var pkg=target.pack();var entry=pkg.entrypoints().get(entryId);
        if(entry==null||entry.side()!=RuntimeResourceSide.CLIENT||!entry.path().startsWith("ui/")||!entry.path().endsWith(".html"))throw new IllegalArgumentException("WORLD_UI_ENTRYPOINT");
        expire();
        var old=views.values().stream().filter(v->v.launch.actorKind()==ActorKind.PLAYER&&v.launch.viewerId().equals(viewer.getUUID())&&v.launch.instanceId().equals(instance)&&v.launch.entityId().equals(entity)&&v.launch.entryPath().equals(entry.path())&&valid(v)).findFirst().orElse(null);
        if(old!=null){send(viewer,old.launch.id(),"worldUiLaunch",old.launch);return;}
        if(views.size()>=128||views.values().stream().filter(v->v.launch.viewerId().equals(viewer.getUUID())).count()>=12)throw new IllegalStateException("WORLD_UI_VIEW_BUDGET");
        var launch=new Launch(UUID.randomUUID(),UUID.randomUUID().toString(),pkg.packageId(),pkg.revision(),pkg.version(),entry.path(),MineAgentRuntimeServices.worldId(server),viewer.getUUID(),instance,entity,part,pkg.canonicalSha256(),System.currentTimeMillis()+60_000);
        views.put(launch.viewId(),new View(launch,viewer));send(viewer,launch.id(),"worldUiLaunch",launch);
    }
    public Launch agentLaunch(ServerPlayer viewer,Session source,dev.mineagent.runtime.neoforge.body.MineAgentPlayer actor,dev.mineagent.runtime.api.task.ManagedTask task)throws Exception{
        requireThread();var v=view(source);if(v.launch.actorKind()!=ActorKind.PLAYER||v.actor!=viewer||actor==null||!task.agentId().equals(actor.getUUID())||!task.ownerPlayerId().equals(viewer.getUUID())||agentInteraction!=null)throw new SecurityException("WORLD_UI_AGENT_CONTEXT");
        var entity=actor.level().getEntity(v.launch.entityId());if(!(entity instanceof dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity object)||!agentCanReach(actor,object))throw new IllegalStateException("WORLD_UI_AGENT_OUT_OF_REACH");
        WorldContentRuntime.get(server).uiTarget(v.launch.instanceId(),v.launch.entityId(),v.launch.part(),actor);
        // Consume the original PLAYER grant before a Native handler can have effects. A retry cannot interact twice.
        views.remove(v.launch.viewId(),v);sessions.close(viewer.getUUID(),source.sessionId());transfers.values().removeIf(x->x==v);
        var pending=new AgentInteraction(v,actor,task);agentInteraction=pending;
        try{var result=WorldContentRuntime.get(server).withObjectInteractionOrigin(task,actor,object,()->actor.interactOn(object,net.minecraft.world.InteractionHand.MAIN_HAND,object.getBoundingBox().getCenter().subtract(object.position())));
            if(!result.consumesAction()||pending.offered==null||pending.error!=null)throw new IllegalStateException(pending.error==null?"WORLD_UI_AGENT_NOT_OFFERED":pending.error);
            var launched=new View(pending.offered,actor);if(!valid(launched))throw new IllegalStateException("WORLD_UI_AGENT_TARGET_CHANGED");views.put(launched.launch.viewId(),launched);return launched.launch;
        }catch(Exception|LinkageError error){retireSourceNotice(viewer,source);throw error;}finally{agentInteraction=null;}
    }
    public void retireSourceNotice(ServerPlayer viewer,Session source){send(viewer,UUID.randomUUID(),"packageViewOutdated",Map.of("viewId",source.binding().viewId(),"sessionId",source.sessionId()));}
    private boolean agentCanReach(ServerPlayer actor,dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity object){return actor.isAlive()&&!actor.isSpectator()&&actor.level()==object.level()&&actor.isWithinEntityInteractionRange(object,0)&&actor.hasLineOfSight(object);}
    public boolean known(Binding binding){var v=views.get(binding.viewId());return v!=null&&v.launch.binding().equals(binding)&&valid(v);}
    public void attachAgent(Launch launch,Session session){requireThread();var v=views.get(launch.viewId());if(v==null||v.session!=null||launch.actorKind()!=ActorKind.AGENT||!session.binding().equals(launch.binding()))throw new SecurityException("WORLD_UI_AGENT_SESSION");v.session=session;v.expires=session.expiresAtMillis();}
    public void cancelLaunch(UUID viewer,Launch launch){requireThread();var v=views.get(launch.viewId());if(v!=null&&v.launch.viewerId().equals(viewer))closeView(v);}
    public Code authorize(Session session){
        var v=views.get(session.binding().viewId());return v!=null&&session.binding().equals(v.launch.binding())&&(v.session==null||v.session.sessionId().equals(session.sessionId()))&&valid(v)?Code.OK:Code.STALE_VIEW;
    }
    private boolean valid(View view){
        if(closed||view.expires<=System.currentTimeMillis())return false;
        var viewer=server.getPlayerList().getPlayer(view.launch.viewerId());if(viewer==null)return false;
        try{
            if(view.launch.actorKind()==ActorKind.PLAYER?view.actor!=viewer:MineAgentRuntimeServices.bodies(server).body(view.launch.actorId()).orElse(null)!=view.actor)return false;
            var target=WorldContentRuntime.get(server).uiTarget(view.launch.instanceId(),view.launch.entityId(),view.launch.part(),view.actor);
            if(view.launch.actorKind()==ActorKind.AGENT&&(!(view.actor.level().getEntity(view.launch.entityId()) instanceof dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity e)||!agentCanReach(view.actor,e)))return false;
            return target.pack().revision()==view.launch.packageRevision()&&target.pack().canonicalSha256().equals(view.launch.canonicalSha256());
        }catch(RuntimeException invalid){return false;}
    }
    private View launch(UUID viewer,UUID id){var v=views.values().stream().filter(x->x.launch.id().equals(id)).findFirst().orElseThrow(()->new SecurityException("WORLD_UI_GRANT_MISSING"));if(!v.launch.viewerId().equals(viewer)||!valid(v))throw new SecurityException("WORLD_UI_GRANT_REVOKED");return v;}
    public void open(ServerPlayer viewer,Request request){
        requireThread();var v=launch(viewer.getUUID(),UUID.fromString(request.arguments().get("launchId")));if(v.preparing||v.offered)throw new IllegalStateException("WORLD_UI_ALREADY_OPEN");
        var pkg=WorldContentRuntime.get(server).uiTarget(v.launch.instanceId(),v.launch.entityId(),v.launch.part(),v.actor).pack();var content=ServerPackageRuntime.get(server).worldContent();v.preparing=true;
        try{java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return PackagePreviewBundle.encode(pkg,v.launch.entryPath(),content);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}},io)
            .whenComplete((bytes,error)->server.execute(()->{
                v.preparing=false;if(closed)return;Receipt result;
                try{
                    if(error!=null||!valid(v)||sessions.checkRead(viewer.getUUID(),request,"worldui.open")!=Code.OK)throw new SecurityException("WORLD_UI_GRANT_REVOKED");
                    if(v.session==null)v.session=sessions.open(v.launch.binding(),true,true,1_800_000);else if(sessions.get(viewer.getUUID(),v.session.sessionId()).isEmpty())throw new SecurityException("WORLD_UI_AGENT_REVOKED");v.expires=v.session.expiresAtMillis();v.offered=true;
                    var offer=leases.offer(viewer.getUUID(),request.sessionId(),pkg.packageId(),pkg.revision(),bytes);transfers.put(offer.transferId(),v);
                    result=sessions.complete(request,Code.ACCEPTED,Map.of("packageId",pkg.packageId().toString(),"packageRevision",Long.toString(pkg.revision()),"transferId",offer.transferId().toString(),"sha256",offer.sha256(),"size",Integer.toString(offer.size()),"contentSession",json.writeValueAsString(v.session)));
                }catch(Exception failed){closeView(v);result=sessions.complete(request,Code.FAILED,Map.of("errorCode","WORLD_UI_OPEN_FAILED"));}
                send(viewer,request.operationId(),"receipt",result);
            }));}catch(RuntimeException failed){v.preparing=false;throw failed;}
    }
    public Map<String,String> chunk(ServerPlayer viewer,UUID session,UUID transfer,int offset){requireThread();byte[] bytes=leases.chunk(viewer.getUUID(),session,transfer,offset,offer->{var v=transfers.get(offer.transferId());return v!=null&&valid(v);});return Map.of("offset",Integer.toString(offset),"bytes",Base64.getEncoder().encodeToString(bytes));}
    public void release(UUID viewer){requireThread();leases.release(viewer);transfers.values().removeIf(v->v.launch.viewerId().equals(viewer));}
    public List<Session> pushTargets(dev.mineagent.runtime.core.events.StatePushConsumer target,dev.mineagent.runtime.core.events.RuntimeEventStore.Event source)throws Exception{
        Objects.requireNonNull(source);return pushTargets(target.packageId(),target.instanceId(),target.packageRevision(),target.canonicalSha256(),target.entryPath(),source);
    }
    public List<Session> schedulePushTargets(dev.mineagent.runtime.core.scheduling.SchedulePushConsumer target,dev.mineagent.runtime.core.events.RuntimeEventStore.Event condition)throws Exception{
        return pushTargets(target.packageId(),target.instanceId(),target.packageRevision(),target.canonicalSha256(),target.entryPath(),condition);
    }
    private List<Session> pushTargets(UUID packageId,UUID instanceId,long revision,String canonical,String entry,dev.mineagent.runtime.core.events.RuntimeEventStore.Event source)throws Exception{
        requireThread();var result=new ArrayList<Session>();
        for(var view:views.values()){
            var launch=view.launch;if(!view.pushListening||view.session==null||!valid(view)||!launch.packageId().equals(packageId)||!launch.instanceId().equals(instanceId)||launch.packageRevision()!=revision||!launch.canonicalSha256().equals(canonical)||!launch.entryPath().equals(entry))continue;
            var current=sessions.get(launch.viewerId(),view.session.sessionId()).orElse(null);if(current==null||current.status()!=Status.RENDERED||view.pushPage!=current.pageGeneration()||view.pushControl!=current.controlEpoch()||source!=null&&!MineAgentRuntimeServices.sharedStates(server).visiblePushChange(launch,source))continue;
            result.add(current);if(result.size()>32)throw new IllegalStateException("STATE_PUSH_RECIPIENT_BUDGET");
        }
        return List.copyOf(result);
    }
    public boolean notifyPush(dev.mineagent.runtime.core.events.RuntimeEventStore.PushDelivery delivery){
        return notifyPush(new dev.mineagent.runtime.api.ui.StatePushToken(false,delivery.id()),delivery.session(),delivery.expiresAt());
    }
    public boolean notifySchedulePush(dev.mineagent.runtime.core.scheduling.PersistentScheduleStore.PushDelivery delivery){
        return notifyPush(new dev.mineagent.runtime.api.ui.StatePushToken(true,delivery.id()),delivery.session(),delivery.expiresAt());
    }
    private boolean notifyPush(dev.mineagent.runtime.api.ui.StatePushToken token,Session captured,long expires){
        requireThread();var view=views.get(captured.binding().viewId());if(view==null||view.session==null||!view.pushListening||!valid(view))return false;
        var current=sessions.get(captured.binding().viewerPlayerId(),captured.sessionId()).orElse(null);if(current==null||current.status()!=Status.RENDERED||view.pushPage!=current.pageGeneration()||view.pushControl!=current.controlEpoch()||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(captured,current))return false;
        var viewer=server.getPlayerList().getPlayer(captured.binding().viewerPlayerId());if(viewer==null)return false;
        send(viewer,token.id(),"worldUiStatePush",Map.of("token",token.wire(),"viewId",captured.binding().viewId(),"sessionId",captured.sessionId(),"serverInstanceId",captured.serverInstanceId(),"pageGeneration",captured.pageGeneration(),"controlEpoch",captured.controlEpoch(),"expiresAt",expires));return true;
    }
    public Map<String,String> read(ServerPlayer viewer,Session session,UUID operation)throws Exception{return read(viewer,session,operation,Map.of());}
    public Map<String,String> read(ServerPlayer viewer,Session session,UUID operation,Map<String,String> arguments)throws Exception{
        requireThread();var view=view(session);var events=MineAgentRuntimeServices.events(server).store();
        if(arguments.keySet().equals(Set.of("pushAck"))){var token=dev.mineagent.runtime.api.ui.StatePushToken.parse(arguments.get("pushAck"));if(token.schedule())MineAgentRuntimeServices.schedules(server).store().acknowledgePushRead(token.id(),session);else events.acknowledgePushRead(token.id(),session);return Map.of("pushAck","READ_DELIVERED_NOT_PAINT_OR_BUSINESS");}
        var tokens=new ArrayList<dev.mineagent.runtime.api.ui.StatePushToken>();boolean enableAfterRead=false;long listenAttempt=0;
        if(arguments.keySet().equals(Set.of("listen","listenRevision"))){if(!Set.of("true","false").contains(arguments.get("listen")))throw new IllegalArgumentException("WORLD_UI_PUSH_LISTEN");long revision=Long.parseLong(arguments.get("listenRevision"));if(revision<1||revision>9007199254740991L)throw new IllegalArgumentException("WORLD_UI_PUSH_LISTEN_REVISION");if(view.pushPage!=session.pageGeneration()||view.pushControl!=session.controlEpoch()){view.pushListenRevision=0;view.pushListening=false;}if(revision<view.pushListenRevision||revision==view.pushListenRevision&&view.pushListening!=Boolean.parseBoolean(arguments.get("listen")))throw new SecurityException("WORLD_UI_PUSH_LISTEN_STALE");view.pushListenRevision=revision;view.pushPage=session.pageGeneration();view.pushControl=session.controlEpoch();enableAfterRead=Boolean.parseBoolean(arguments.get("listen"));listenAttempt=revision;if(!enableAfterRead)view.pushListening=false;}
        else if(arguments.keySet().equals(Set.of("pushTokens"))){
            if(!view.pushListening)throw new SecurityException("STATE_PUSH_NOT_LISTENING");var array=json.readTree(arguments.get("pushTokens"));if(!array.isArray()||array.isEmpty()||array.size()>16)throw new IllegalArgumentException("STATE_PUSH_TOKEN_BATCH");
            for(var token:array){if(!token.isTextual())throw new IllegalArgumentException("STATE_PUSH_TOKEN");var parsed=dev.mineagent.runtime.api.ui.StatePushToken.parse(token.asText());if(parsed.schedule())MineAgentRuntimeServices.schedules(server).store().requirePushRead(parsed.id(),session);else events.requirePushRead(parsed.id(),session);tokens.add(parsed);}if(new HashSet<>(tokens).size()!=tokens.size())throw new IllegalArgumentException("STATE_PUSH_DUPLICATE_TOKEN");
        }else if(!arguments.isEmpty())throw new IllegalArgumentException("WORLD_UI_READ_ARGUMENTS");
        budget();var reply=WorldContentRuntime.get(server).dispatchUi(view.launch,view.actor,true,"","{}",operation);var result=new LinkedHashMap<>(state(reply.revision(),reply.json()));if(enableAfterRead&&view.pushListenRevision==listenAttempt&&view.pushPage==session.pageGeneration()&&view.pushControl==session.controlEpoch()&&valid(view))view.pushListening=true;
        if(!tokens.isEmpty()){
            var eventTokens=tokens.stream().filter(t->!t.schedule()).map(dev.mineagent.runtime.api.ui.StatePushToken::id).toList();var scheduleTokens=tokens.stream().filter(dev.mineagent.runtime.api.ui.StatePushToken::schedule).map(dev.mineagent.runtime.api.ui.StatePushToken::id).toList();
            // Separate ledger transactions: return a proof only after both groups are recorded. This is not cross-store atomicity.
            if(!eventTokens.isEmpty())events.pushReadProduced(eventTokens,session,reply.revision());if(!scheduleTokens.isEmpty())MineAgentRuntimeServices.schedules(server).store().pushReadProduced(scheduleTokens,session,reply.revision());
            result.put("pushReadTokens",json.writeValueAsString(tokens.stream().map(dev.mineagent.runtime.api.ui.StatePushToken::wire).toList()));
        }return result;
    }
    public Map<String,String> act(ServerPlayer viewer,Session session,Request request)throws Exception{
        requireThread();var v=view(session);var a=request.arguments();if(!a.keySet().equals(Set.of("name","payload","expectedRevision")))throw new IllegalArgumentException("WORLD_UI_ARGUMENTS");
        var input=new WorldUiActionJournal.Input(request.operationId(),viewer.getUUID(),v.launch.instanceId(),v.launch.entityId(),v.launch.part(),v.launch.entryPath(),v.launch.canonicalSha256(),Long.parseLong(a.get("expectedRevision")),a.get("name"),a.get("payload"),v.launch.actorId(),v.launch.actorKind(),v.launch.taskId(),v.launch.taskRevision());
        SharedMultiplayerSmokeServer.observe(server,v.launch,request);
        budget();var world=WorldContentRuntime.get(server);var prepared=actions.prepare(input,world.instance(v.launch.instanceId()).orElseThrow().revision(),valid(v));
        if(!prepared.execute()){var record=prepared.record();if(!record.state().equals("APPLIED"))throw new IllegalStateException("WORLD_UI_OUTCOME_UNKNOWN");return state(record.afterRevision(),record.response());}
        try{var reply=world.dispatchUi(v.launch,v.actor,false,input.action(),input.payload(),input.operationId());actions.complete(input,reply.revision(),reply.json());return state(reply.revision(),reply.json());}
        catch(Exception failed){actions.unknown(input,failed.getMessage());throw failed;}
    }
    private Map<String,String> state(long revision,String value)throws Exception{return Map.of("state",json.writeValueAsString(Map.of("revision",revision,"data",json.readTree(value),"executionMode","PACKAGE_WORLD_UI_EVENT")));}
    private View view(Session session){if(authorize(session)!=Code.OK)throw new SecurityException("WORLD_UI_GRANT_REVOKED");return views.get(session.binding().viewId());}
    private void budget(){int tick=server.getTickCount();if(tick!=callbackTick){callbackTick=tick;callbacks=0;}if(++callbacks>8)throw new IllegalStateException("WORLD_UI_CALLBACK_BUDGET");}
    public void closeSession(UUID viewer,UUID id){requireThread();for(var v:List.copyOf(views.values()))if(v.launch.viewerId().equals(viewer)&&v.session!=null&&v.session.sessionId().equals(id))closeView(v);}
    public void disconnect(UUID viewer){requireThread();for(var v:List.copyOf(views.values()))if(v.launch.viewerId().equals(viewer))closeView(v);release(viewer);}
    public void invalidateInstance(UUID instance){requireThread();for(var v:List.copyOf(views.values()))if(v.launch.instanceId().equals(instance))closeView(v);}
    private void closeView(View v){views.remove(v.launch.viewId(),v);transfers.values().removeIf(x->x==v);if(v.session!=null){try{sessions.close(v.launch.viewerId(),v.session.sessionId());}catch(RuntimeException ignored){}var viewer=server.getPlayerList().getPlayer(v.launch.viewerId());if(viewer!=null)send(viewer,UUID.randomUUID(),"packageViewOutdated",Map.of("viewId",v.launch.viewId(),"sessionId",v.session.sessionId()));}}
    public void expire(){requireThread();for(var v:List.copyOf(views.values()))if(!valid(v)||v.session!=null&&sessions.get(v.launch.viewerId(),v.session.sessionId()).map(s->s.status()==Status.CLOSED).orElse(true))closeView(v);}
    private void requireThread(){if(closed||!server.isSameThread())throw new IllegalStateException("WORLD_UI_UNAVAILABLE");}
    private void send(ServerPlayer viewer,UUID id,String channel,Object value){try{PacketDistributor.sendToPlayer(viewer,new UiPayloads.Event(id,channel,json.writeValueAsString(value)));}catch(Exception e){throw new IllegalStateException("WORLD_UI_SEND",e);}}
    @Override public void close(){closed=true;io.shutdownNow();views.clear();transfers.clear();leases.clear();try{actions.close();}catch(Exception e){throw new IllegalStateException("WORLD_UI_CLOSE",e);}}
}
