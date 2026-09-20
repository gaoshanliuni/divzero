package dev.mineagent.runtime.neoforge.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.scripting.ManagedScriptRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Executes package-owned server JS in per-instance scopes of the existing Rhino runtime. No object-specific generators. */
public final class WorldContentRuntime implements AutoCloseable {
    private static final Map<MinecraftServer,WorldContentRuntime> RUNTIMES=new IdentityHashMap<>();
    private final MinecraftServer server;private final ServerPackageRuntime packages;private final RuntimeInstanceService instances;
    private final WorldActivationLedger ledger;private final ManagedScriptRuntime scripts=new ManagedScriptRuntime();
    private final WorldInstanceMoveJournal moves;
    private dev.mineagent.runtime.core.shared.SharedStateStore sharedState;
    private final Map<UUID,InstanceHost> hosts=new LinkedHashMap<>();
    private final ObjectMapper json=new ObjectMapper();private boolean closed;
    private final Map<String,dev.mineagent.runtime.core.objects.RuntimeModelBundle> objectAssets=new LinkedHashMap<>();
    // addFreshEntity may defer query visibility until the end of the current entity tick.
    // Keep real carrier references for collision admission only, never for object()/verifiedObjects().
    private final Map<UUID,RuntimeObjectEntity> pendingObjectSpawns=new LinkedHashMap<>();
    public record ObjectPart(UUID entity,String model,String hash,double dx,double dy,double dz,String state){}
    public record ObjectInteraction(String part,ServerPlayer player,UUID operationId){public ObjectInteraction(String part,ServerPlayer player){this(part,player,UUID.randomUUID());}}
    private record InteractionOrigin(ServerPlayer actor,RuntimeObjectEntity object,dev.mineagent.runtime.api.task.ManagedTask task,dev.mineagent.runtime.core.shared.SharedStateStore.Provenance provenance){}
    private InteractionOrigin interactionOrigin;
    public record ObjectEventDescriptor(dev.mineagent.runtime.core.events.ObjectInteractionFilter.Target target,UUID owner,UUID activationId,RuntimeObjectEntity object){}
    public record UiTarget(RuntimePackage pack,UUID instance,UUID entity,String part){}
    public static final class UiEvent {
        private final ServerPlayer player;private final String part,action,payload;private final UUID operation;private final long revision;private String response;
        UiEvent(ServerPlayer player,String part,String action,String payload,UUID operation,long revision){this.player=player;this.part=part;this.action=action;this.payload=payload;this.operation=operation;this.revision=revision;}
        public ServerPlayer player(){return player;}public String part(){return part;}public String action(){return action;}public String payload(){return payload;}public UUID operationId(){return operation;}public long revision(){return revision;}
        public void reply(String json){if(response!=null)throw new IllegalStateException("WORLD_UI_REPLY_ONCE");response=dev.mineagent.runtime.core.ui.WorldUiData.normalize(json);}
    }
    public record UiReply(long revision,String json){}
    private int restoreCursor;
    private enum Phase { LEGACY_LOAD, REGISTERING, CREATING, RESTORING, ACTIVE, MOVING }
    private WorldContentRuntime(MinecraftServer server)throws Exception{
        this.server=server;packages=ServerPackageRuntime.get(server);var db=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        ledger=WorldActivationLedger.open(db,MineAgentRuntimeServices.worldId(server));
        try{instances=RuntimeInstanceService.open(db,MineAgentRuntimeServices.worldId(server),java.time.Clock.systemUTC(),packages.worldLibrary());}
        catch(Exception e){ledger.close();throw e;}
        WorldInstanceMoveJournal loadedMoves=null;
        try{loadedMoves=WorldInstanceMoveJournal.open(db,MineAgentRuntimeServices.worldId(server));
            for(var move:loadedMoves.unresolved()){
                var a=ledger.get(move.input().owner(),move.input().activation()).orElseThrow();
                if(!a.instanceId().equals(move.input().instance())||!a.canonicalSha256().equals(move.input().canonical()))throw new IllegalStateException("INSTANCE_MOVE_RECOVERY_CONTEXT");
                if(Set.of("ACTIVE","RESTORE_PENDING","RESTORING").contains(a.state()))ledger.finish(a.operationId(),"INTERRUPTED","INSTANCE_MOVE_INTERRUPTED");
            }
            moves=loadedMoves;
        }catch(Exception e){if(loadedMoves!=null)loadedMoves.close();instances.close();ledger.close();throw e;}
    }
    public static synchronized WorldContentRuntime get(MinecraftServer server){
        if(!server.isSameThread())throw new IllegalStateException("WORLD_THREAD_REQUIRED");
        var old=RUNTIMES.get(server);if(old!=null)return old;
        try{var next=new WorldContentRuntime(server);RUNTIMES.put(server,next);return next;}catch(Exception e){throw new IllegalStateException("WORLD_CONTENT_STORE",e);}
    }
    public static void tick(MinecraftServer server){var runtime=RUNTIMES.get(server);if(runtime!=null)runtime.tick();}
    public static synchronized void stop(MinecraftServer server){var runtime=RUNTIMES.remove(server);if(runtime!=null)try{runtime.close();}catch(Exception e){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("World content close failed: {}",e.getClass().getSimpleName());}}
    public List<WorldActivationLedger.Activation> list(UUID owner){requireThread();return ledger.all().stream().filter(a->a.owner().equals(owner)).toList();}
    public Optional<RuntimeInstance> instance(UUID id){requireThread();return instances.get(id);}
    public record SharedDescriptor(UUID packageId,UUID instanceId,UUID owner,UUID activationId,long packageRevision,String canonicalSha256){}
    public SharedDescriptor sharedDescriptor(UUID id){requireThread();var h=hosts.get(id);if(h==null||h.phase!=Phase.ACTIVE||!h.current())throw new IllegalStateException("SHARED_INSTANCE_NOT_ACTIVE");var p=packages.worldLibrary().get(h.activation.packageId()).orElseThrow();if(!p.permissions().contains("state.shared"))throw new SecurityException("SHARED_PACKAGE_PERMISSION_REQUIRED");return new SharedDescriptor(p.packageId(),id,h.activation.owner(),h.activation.operationId(),p.revision(),p.canonicalSha256());}
    /** No runtime creation or package execution. Null means retain the last committed expiry binding, not cancel TTL. */
    public static dev.mineagent.runtime.core.shared.SharedStateStore.ExpiryBinding sharedExpiryBinding(MinecraftServer server,dev.mineagent.runtime.core.shared.SharedStateStore.Scope scope){
        if(!server.isSameThread()||!scope.world().equals(MineAgentRuntimeServices.worldId(server)))throw new SecurityException("SHARED_EXPIRY_WORLD_THREAD");
        var runtime=RUNTIMES.get(server);if(runtime==null)return null;var host=runtime.hosts.get(scope.instance());
        if(host==null||host.phase!=Phase.ACTIVE||!host.current()||!host.activation.packageId().equals(scope.pack()))return null;
        var pack=runtime.packages.worldLibrary().get(scope.pack()).orElseThrow();if(!pack.permissions().contains("state.shared"))return null;
        return new dev.mineagent.runtime.core.shared.SharedStateStore.ExpiryBinding(host.activation.owner(),pack.revision(),pack.canonicalSha256());
    }
    /** Separate display-feedback grant, not a World UI proximity bypass. Package code produces data, Native commits it later. */
    public String planFeedback(dev.mineagent.runtime.core.feedback.UiFeedbackStore.Scope scope,UUID feedbackId,String payload,String snapshot){
        requireThread();var binding=Objects.requireNonNull(scope.dataBinding());var descriptor=sharedDescriptor(binding.instanceId());
        if(!descriptor.owner().equals(scope.ownerId())||!descriptor.packageId().equals(scope.packageId())||descriptor.packageRevision()!=scope.packageRevision()||!descriptor.canonicalSha256().equals(scope.canonicalSha256()))throw new SecurityException("FEEDBACK_INSTANCE_CONTEXT");
        var host=hosts.get(binding.instanceId());if(host.feedbackPlanning)throw new IllegalStateException("FEEDBACK_REENTRANT_PLAN");boolean previous=host.uiReadOnly;host.uiReadOnly=true;host.feedbackPlanning=true;
        var event=new FeedbackPlanEvent(feedbackId,scope.authorId(),scope.eventName(),payload,snapshot);
        try{if(!scripts.fireTo(binding.instanceId(),"feedback.plan",event)||!host.current()||event.plan==null)throw new IllegalStateException("FEEDBACK_PLAN_HANDLER_FAILED");return event.plan;}
        finally{host.feedbackPlanning=false;host.uiReadOnly=previous;}
    }
    public static final class FeedbackPlanEvent {
        private final UUID feedbackId,author;private final String name,payload,snapshot;private String plan;
        FeedbackPlanEvent(UUID feedbackId,UUID author,String name,String payload,String snapshot){this.feedbackId=feedbackId;this.author=author;this.name=name;this.payload=payload;this.snapshot=snapshot;}
        public UUID feedbackId(){return feedbackId;}public UUID authorId(){return author;}public String name(){return name;}public String payload(){return payload;}public String snapshot(){return snapshot;}
        public void plan(String value){if(plan!=null)throw new IllegalStateException("FEEDBACK_PLAN_ONCE");if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("FEEDBACK_PLAN_BUDGET");plan=value;}
    }
    public int verifiedBlocks(UUID instance){requireThread();var host=hosts.get(instance);return host==null?0:host.blockCount();}
    public int verifiedObjects(UUID instance){requireThread();var host=hosts.get(instance);return host==null?0:host.objectCount();}
    public boolean objectActive(RuntimeObjectEntity entity){
        requireThread();if(entity.header()==null)return false;var h=hosts.get(entity.header().instance());
        if(h==null||h.phase!=Phase.ACTIVE||!h.current())return false;
        try{var part=part(instances.get(entity.header().instance()).orElseThrow(),entity.header().part());if(part==null||!part.state().equals("ACTIVE")||!matches(entity,part)||entity.level()!=h.level)return false;objectBundle(entity);return true;}catch(Exception invalid){return false;}
    }
    /** Metadata for the owner's declared parts; unloaded targets remain explicitly unavailable, not silently absent. */
    public List<ObjectEventDescriptor> objectEventTargets(UUID owner,UUID instance)throws Exception{
        requireThread();var activation=ledger.all().stream().filter(a->a.instanceId().equals(instance)).findFirst().orElseThrow(()->new IllegalStateException("OBJECT_EVENT_INSTANCE_MISSING"));
        if(!activation.owner().equals(owner))throw new SecurityException("OBJECT_EVENT_OWNER_REQUIRED");var value=instances.get(instance).orElseThrow();var host=hosts.get(instance);var result=new ArrayList<ObjectEventDescriptor>();
        for(var key:value.state().keySet().stream().filter(k->k.startsWith("_object.")).sorted().toList()){
            var part=part(value,key.substring(8));if(part==null)throw new IllegalStateException("OBJECT_EVENT_RECORD_MISSING");
            var target=new dev.mineagent.runtime.core.events.ObjectInteractionFilter.Target(activation.packageId(),instance,activation.packageRevision(),activation.canonicalSha256(),part.entity(),key.substring(8),part.hash());
            RuntimeObjectEntity object=null;if(host!=null&&host.phase==Phase.ACTIVE&&host.level.getEntity(part.entity()) instanceof RuntimeObjectEntity loaded&&objectActive(loaded))object=loaded;
            result.add(new ObjectEventDescriptor(target,owner,activation.operationId(),object));if(result.size()>32)throw new IllegalStateException("OBJECT_EVENT_PART_BUDGET");
        }
        return List.copyOf(result);
    }
    public ObjectEventDescriptor objectEventTarget(RuntimeObjectEntity entity){
        requireThread();if(!objectActive(entity))throw new IllegalStateException("OBJECT_EVENT_UNAVAILABLE");var host=hosts.get(entity.header().instance());if(host.phase!=Phase.ACTIVE)throw new IllegalStateException("OBJECT_EVENT_INSTANCE_NOT_ACTIVE");
        var target=new dev.mineagent.runtime.core.events.ObjectInteractionFilter.Target(host.activation.packageId(),host.activation.instanceId(),host.packageRevision,host.activation.canonicalSha256(),entity.getUUID(),entity.header().part(),entity.header().asset());
        return new ObjectEventDescriptor(target,host.activation.owner(),host.activation.operationId(),entity);
    }
    public ObjectEventDescriptor objectEventTarget(UUID owner,dev.mineagent.runtime.core.events.ObjectInteractionFilter.Target target){
        requireThread();var host=hosts.get(target.instanceId());if(host==null||!host.activation.owner().equals(owner))throw new SecurityException("OBJECT_EVENT_OWNER_OR_INSTANCE");
        if(!(host.level.getEntity(target.entityId()) instanceof RuntimeObjectEntity entity))throw new IllegalStateException("OBJECT_EVENT_UNLOADED");
        var actual=objectEventTarget(entity);if(!actual.target().equals(target)||!actual.owner().equals(owner))throw new SecurityException("OBJECT_EVENT_TARGET_CHANGED");return actual;
    }
    /** Restore/chunk absence is a waiting source, not permission to replay an old callback. */
    public boolean objectEventWaiting(UUID owner,dev.mineagent.runtime.core.events.ObjectInteractionFilter.Target target){
        requireThread();var activation=ledger.all().stream().filter(a->a.instanceId().equals(target.instanceId())).findFirst().orElse(null);
        if(activation==null||!activation.owner().equals(owner)||!activation.packageId().equals(target.packageId())||activation.packageRevision()!=target.packageRevision()||!activation.canonicalSha256().equals(target.canonicalSha256())||!ownerAllowed(activation))return false;
        var pack=packages.worldLibrary().get(target.packageId()).orElse(null);if(pack==null||!pack.enabled()||pack.revision()!=target.packageRevision()||!pack.canonicalSha256().equals(target.canonicalSha256()))return false;
        if(activation.autoRestore()&&Set.of("RESTORE_PENDING","RESTORING").contains(activation.state()))return true;
        var host=hosts.get(target.instanceId());if(host==null||host.phase!=Phase.ACTIVE||!host.current())return false;
        try{var part=part(instances.get(target.instanceId()).orElseThrow(),target.part());return part!=null&&part.state().equals("ACTIVE")&&part.entity().equals(target.entityId())&&part.hash().equals(target.assetSha256())&&host.level.getEntity(target.entityId())==null;}catch(Exception invalid){return false;}
    }
    public record ScriptEventTarget(UUID packageId,UUID instanceId,UUID owner,long packageRevision,String canonicalSha256,UUID activationId,UUID executionEpoch,boolean active,List<String> handlers){public ScriptEventTarget{handlers=List.copyOf(handlers);}}
    public ScriptEventTarget scriptEventTarget(UUID owner,UUID instance){
        requireThread();var activation=ledger.all().stream().filter(a->a.instanceId().equals(instance)).findFirst().orElseThrow(()->new IllegalStateException("SCRIPT_INSTANCE_MISSING"));if(!activation.owner().equals(owner))throw new SecurityException("SCRIPT_INSTANCE_OWNER_REQUIRED");
        var host=hosts.get(instance);boolean active=host!=null&&host.phase==Phase.ACTIVE&&host.current();return new ScriptEventTarget(activation.packageId(),instance,owner,activation.packageRevision(),activation.canonicalSha256(),activation.operationId(),active?host.eventEpoch:null,active,active?scripts.eventHandlers(instance):List.of());
    }
    public int scriptEventHandlerRevision(UUID owner,UUID instance,String handler){var target=scriptEventTarget(owner,instance);return target.active()?scripts.eventHandlerRevision(instance,handler):0;}
    public ScriptEventTarget scriptScheduleTarget(UUID owner,UUID instance){var target=scriptEventTarget(owner,instance);return new ScriptEventTarget(target.packageId(),instance,owner,target.packageRevision(),target.canonicalSha256(),target.activationId(),target.executionEpoch(),target.active(),target.active()?scripts.scheduleHandlers(instance):List.of());}
    public boolean instanceEventWaiting(UUID owner,UUID packageId,UUID instance,long revision,String canonical){
        requireThread();var activation=ledger.all().stream().filter(a->a.instanceId().equals(instance)).findFirst().orElse(null);if(activation==null||!activation.owner().equals(owner)||!activation.packageId().equals(packageId)||activation.packageRevision()!=revision||!activation.canonicalSha256().equals(canonical)||!ownerAllowed(activation))return false;
        var pack=packages.worldLibrary().get(packageId).orElse(null);return pack!=null&&pack.enabled()&&pack.revision()==revision&&pack.canonicalSha256().equals(canonical)&&activation.autoRestore()&&Set.of("RESTORE_PENDING","RESTORING").contains(activation.state());
    }
    public static final class ScriptEventPayload {
        private final UUID trigger,subscription,event;private final String data;private final java.util.function.BooleanSupplier permit;private boolean active=true,completed;private String result="";
        private ScriptEventPayload(UUID trigger,UUID subscription,UUID event,String data,java.util.function.BooleanSupplier permit){this.trigger=trigger;this.subscription=subscription;this.event=event;this.data=data;this.permit=permit;}
        private void check(){if(!permitted())throw new SecurityException("SCRIPT_EVENT_SCOPE_EXPIRED");}
        private boolean permitted(){return active&&permit.getAsBoolean();}
        public UUID triggerId(){check();return trigger;}public UUID subscriptionId(){check();return subscription;}public UUID eventId(){check();return event;}
        public String json(){check();return data;}
        public void complete(String json){check();if(completed)throw new IllegalStateException("SCRIPT_COMPLETE_ONCE");result=dev.mineagent.runtime.core.events.ScriptEventConsumer.normalizeResult(json);check();completed=true;}
    }
    public record ScriptEventResult(boolean returned,boolean completed,String result){}
    public ScriptEventResult dispatchScriptEvent(UUID owner,dev.mineagent.runtime.core.events.ScriptEventConsumer consumer,UUID trigger,UUID subscription,UUID event,String data,dev.mineagent.runtime.core.shared.SharedStateStore.Provenance origin,java.util.function.BooleanSupplier permit){
        requireThread();var target=scriptEventTarget(owner,consumer.instanceId());if(!target.active()||!target.packageId().equals(consumer.packageId())||target.packageRevision()!=consumer.packageRevision()||!target.canonicalSha256().equals(consumer.canonicalSha256())||!target.handlers().contains(consumer.handler())||!permit.getAsBoolean())throw new SecurityException("SCRIPT_TARGET_UNAVAILABLE");
        if(data==null||data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16000||!trigger.equals(origin.consumerTriggerId())||!subscription.equals(origin.consumerSubscriptionId()))throw new IllegalArgumentException("SCRIPT_EVENT_DATA");
        var host=hosts.get(consumer.instanceId());if(host.scriptEvent!=null||host.scriptSchedule!=null||host.uiPlayer!=null||host.sharedPrincipal!=null||host.uiReadOnly)throw new SecurityException("SCRIPT_EVENT_REENTRANT_OR_UI_CONTEXT");
        var previousOperation=host.sharedOperation;var previousOrigin=host.sharedOrigin;var payload=new ScriptEventPayload(trigger,subscription,event,data,permit);host.scriptEvent=payload;host.sharedOperation=trigger;host.sharedOrigin=origin;
        try{boolean returned=scripts.fireConsumer(consumer.instanceId(),consumer.handler(),payload);return new ScriptEventResult(returned,payload.completed,payload.result);}
        finally{payload.active=false;host.scriptEvent=null;host.sharedOperation=previousOperation;host.sharedOrigin=previousOrigin;}
    }
    public static final class ScriptSchedulePayload {
        private final UUID occurrence,schedule;private final String data;private final java.util.function.BooleanSupplier permit;private boolean active=true,completed;private String result="";
        private ScriptSchedulePayload(UUID occurrence,UUID schedule,String data,java.util.function.BooleanSupplier permit){this.occurrence=occurrence;this.schedule=schedule;this.data=data;this.permit=permit;}
        private boolean permitted(){return active&&permit.getAsBoolean();}private void check(){if(!permitted())throw new SecurityException("SCHEDULE_SCRIPT_SCOPE_EXPIRED");}
        public UUID occurrenceId(){check();return occurrence;}public UUID scheduleId(){check();return schedule;}public String json(){check();return data;}
        public void complete(String json){check();if(completed)throw new IllegalStateException("SCHEDULE_SCRIPT_COMPLETE_ONCE");result=dev.mineagent.runtime.core.events.ScriptEventConsumer.normalizeResult(json);check();completed=true;}
    }
    public record ScriptScheduleResult(boolean returned,boolean completed,String result){}
    public ScriptScheduleResult dispatchScriptSchedule(UUID owner,dev.mineagent.runtime.core.scheduling.ScriptScheduleConsumer consumer,UUID occurrence,UUID schedule,String data,dev.mineagent.runtime.core.shared.SharedStateStore.Provenance origin,java.util.function.BooleanSupplier permit){
        requireThread();var target=scriptScheduleTarget(owner,consumer.instanceId());if(!target.active()||!target.packageId().equals(consumer.packageId())||target.packageRevision()!=consumer.packageRevision()||!target.canonicalSha256().equals(consumer.canonicalSha256())||!target.handlers().contains(consumer.handler())||!permit.getAsBoolean())throw new SecurityException("SCHEDULE_SCRIPT_TARGET_UNAVAILABLE");
        if(data==null||data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16000||!occurrence.equals(origin.scheduleOccurrenceId())||!schedule.equals(origin.scheduleDefinitionId()))throw new IllegalArgumentException("SCHEDULE_SCRIPT_DATA");
        var host=hosts.get(consumer.instanceId());if(host.scriptEvent!=null||host.scriptSchedule!=null||host.uiPlayer!=null||host.sharedPrincipal!=null||host.uiReadOnly)throw new SecurityException("SCHEDULE_SCRIPT_REENTRANT_OR_UI_CONTEXT");
        var previousOperation=host.sharedOperation;var previousOrigin=host.sharedOrigin;var payload=new ScriptSchedulePayload(occurrence,schedule,data,permit);host.scriptSchedule=payload;host.sharedOperation=occurrence;host.sharedOrigin=origin;
        try{boolean returned=scripts.fireScheduleConsumer(consumer.instanceId(),consumer.handler(),payload);return new ScriptScheduleResult(returned,payload.completed,payload.result);}
        finally{payload.active=false;host.scriptSchedule=null;host.sharedOperation=previousOperation;host.sharedOrigin=previousOrigin;}
    }
    /** Only the real Native Agent interaction call establishes task provenance. Failure to establish it never invents a cause. */
    public <T>T withObjectInteractionOrigin(dev.mineagent.runtime.api.task.ManagedTask task,ServerPlayer actor,RuntimeObjectEntity object,java.util.function.Supplier<T> action){
        requireThread();var previous=interactionOrigin;interactionOrigin=null;
        try{
            try{var current=MineAgentRuntimeServices.tasks(server).get(task.taskId()).orElse(null);var events=MineAgentRuntimeServices.eventsIfPresent(server);
                if(events!=null&&actor instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer&&task.agentId().equals(actor.getUUID())&&task.worldId().equals(MineAgentRuntimeServices.worldId(server))&&dev.mineagent.runtime.core.task.TaskResultFence.current(task,current)&&current.status()==dev.mineagent.runtime.api.task.TaskStatus.RUNNING)
                    interactionOrigin=new InteractionOrigin(actor,object,task,new dev.mineagent.runtime.core.shared.SharedStateStore.Provenance(task.taskId(),task.intentRevision(),events.causalChain(task)));
            }catch(RuntimeException unavailable){interactionOrigin=null;}
            return action.get();
        }finally{interactionOrigin=previous;}
    }
    public boolean interactObject(RuntimeObjectEntity entity,ServerPlayer player){
        if(player==null||!objectActive(entity)||player.level()!=entity.level()||player.distanceToSqr(entity)>64)return false;
        var h=hosts.get(entity.header().instance());var previous=h.uiPlayer;var prior=h.uiEntity;var oldOperation=h.sharedOperation;var oldOrigin=h.sharedOrigin;var oldPrincipal=h.sharedPrincipal;
        var bound=interactionOrigin;boolean agent=player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
        boolean known=!agent||bound!=null&&bound.actor()==player&&bound.object()==entity&&dev.mineagent.runtime.core.task.TaskResultFence.current(bound.task(),MineAgentRuntimeServices.tasks(server).get(bound.task().taskId()).orElse(null));
        var origin=agent&&known?bound.provenance():dev.mineagent.runtime.core.shared.SharedStateStore.Provenance.NONE;UUID operation=UUID.randomUUID();
        var events=MineAgentRuntimeServices.eventsIfPresent(server);var capture=events==null?null:events.captureObjectInteraction(entity,player,operation,known,origin);
        h.uiPlayer=player;h.uiEntity=entity;h.sharedPrincipal=player;h.sharedOperation=operation;h.sharedOrigin=origin;String outcome="THREW";
        try{boolean result=scripts.fireTo(entity.header().instance(),"object.interact",new ObjectInteraction(entity.header().part(),player,operation));outcome=result?"RETURNED_TRUE":"RETURNED_FALSE";return result;}
        finally{h.uiPlayer=previous;h.uiEntity=prior;h.sharedOperation=oldOperation;h.sharedOrigin=oldOrigin;h.sharedPrincipal=oldPrincipal;if(events!=null)events.completeObjectInteraction(capture,outcome);}
    }
    public UiTarget uiTarget(UUID instance,UUID entity,String part,ServerPlayer viewer){
        requireThread();var h=hosts.get(instance);if(h==null||!h.current()||viewer==null||viewer.level()!=h.level||!(h.level.getEntity(entity) instanceof RuntimeObjectEntity object)||!objectActive(object)||!object.header().instance().equals(instance)||!object.header().part().equals(part)||viewer.distanceToSqr(object)>64)throw new SecurityException("WORLD_UI_TARGET_UNAVAILABLE");
        return new UiTarget(packages.worldLibrary().get(h.activation.packageId()).orElseThrow(),instance,entity,part);
    }
    public UiReply dispatchUi(dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch launch,ServerPlayer viewer,boolean read,String action,String payload,UUID operation){
        if(viewer==null||!viewer.getUUID().equals(launch.actorId())||launch.actorKind()==dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.AGENT&&!(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))throw new SecurityException("WORLD_UI_ACTOR_CONTEXT");
        var target=uiTarget(launch.instanceId(),launch.entityId(),launch.part(),viewer);if(target.pack().revision()!=launch.packageRevision()||!target.pack().canonicalSha256().equals(launch.canonicalSha256()))throw new SecurityException("WORLD_UI_CODE_CHANGED");
        var h=hosts.get(target.instance());var oldPlayer=h.uiPlayer;var oldEntity=h.uiEntity;boolean oldRead=h.uiReadOnly;
        var oldPrincipal=h.sharedPrincipal;var oldOperation=h.sharedOperation;h.sharedPrincipal=viewer;h.sharedOperation=operation;
        var event=new UiEvent(viewer,target.part(),action,payload,operation,instances.get(target.instance()).orElseThrow().revision());
        h.uiReadOnly=read;h.uiPlayer=read?null:viewer;h.uiEntity=read?null:(RuntimeObjectEntity)h.level.getEntity(target.entity());
        try{
            if(!scripts.fireTo(target.instance(),read?"ui.read":"ui.action",event)||!h.current())throw new IllegalStateException("WORLD_UI_HANDLER_FAILED");
            if(event.response==null)throw new IllegalStateException("WORLD_UI_NO_REPLY");return new UiReply(instances.get(target.instance()).orElseThrow().revision(),event.response);
        }finally{h.uiReadOnly=oldRead;h.uiPlayer=oldPlayer;h.uiEntity=oldEntity;h.sharedPrincipal=oldPrincipal;h.sharedOperation=oldOperation;}
    }
    private ObjectPart part(RuntimeInstance instance,String key)throws Exception{String value=instance.state().get("_object."+key);return value==null?null:json.readValue(value,ObjectPart.class);}
    private boolean matches(RuntimeObjectEntity entity,ObjectPart part){return entity.getUUID().equals(part.entity())&&entity.modelPath().equals(part.model())&&entity.header()!=null&&entity.header().asset().equals(part.hash());}
    private dev.mineagent.runtime.core.objects.RuntimeModelBundle model(RuntimePackage pack,RuntimeDefinition definition,String path)throws Exception{
        String key=pack.canonicalSha256()+"|"+definition.definitionId()+"|"+path;var bundle=objectAssets.get(key);if(bundle!=null)return bundle;
        bundle=dev.mineagent.runtime.core.objects.RuntimeModelBundle.load(pack,definition,path,packages.worldContent());
        if(objectAssets.size()>=128||objectAssets.values().stream().mapToLong(dev.mineagent.runtime.core.objects.RuntimeModelBundle::size).sum()+bundle.size()>64L*1024*1024)throw new IllegalStateException("OBJECT_ASSET_BUDGET");objectAssets.put(key,bundle);return bundle;
    }
    public dev.mineagent.runtime.core.objects.RuntimeModelBundle objectBundle(RuntimeObjectEntity entity)throws Exception{
        requireThread();if(entity.header()==null)throw new IllegalStateException("OBJECT_UNBOUND");var instance=instances.get(entity.header().instance()).orElseThrow();
        var activation=ledger.all().stream().filter(a->a.instanceId().equals(instance.instanceId())).findFirst().orElseThrow();var pack=packages.worldVersion(instance.packageId(),activation.canonicalSha256()).orElseThrow();
        var record=part(instance,entity.header().part());if(record==null||!matches(entity,record)||!pack.canonicalSha256().equals(activation.canonicalSha256())||!instance.worldId().equals(MineAgentRuntimeServices.worldId(server)))throw new SecurityException("OBJECT_RESOURCE_BINDING");
        var bundle=model(pack,pack.definitions().get(instance.definitionId()),record.model());if(!bundle.sha256().equals(entity.header().asset())||!bundle.mesh().collision().equals(entity.header().collision())||!bundle.mesh().physics().equals(entity.header().physics()))throw new IllegalStateException("OBJECT_RESOURCE_CHANGED");return bundle;
    }
    private boolean objectsLoaded(RuntimeInstance instance,ServerLevel level)throws Exception{
        for(var entry:instance.state().entrySet())if(entry.getKey().startsWith("_object.")){var part=json.readValue(entry.getValue(),ObjectPart.class);if(!part.state().equals("ACTIVE"))throw new IllegalStateException("OBJECT_OUTCOME_UNKNOWN");var entity=level.getEntity(part.entity());if(entity==null)return false;if(!(entity instanceof RuntimeObjectEntity object)||!matches(object,part))throw new IllegalStateException("OBJECT_RESTORE_MISMATCH");}return true;
    }
    public boolean active(UUID instance){requireThread();var host=hosts.get(instance);return host!=null&&host.current()&&ownerAllowed(host.activation)&&scripts.isLoaded(instance);}
    public record MoveEvent(UUID operationId,RuntimeInstance before,RuntimeInstance after){}
    public record MoveResult(WorldInstanceMoveJournal.Record receipt,RuntimeInstanceLocation location,long instanceRevision,boolean duplicate){}
    public boolean mayManageInstance(ServerPlayer viewer,UUID activation){requireThread();try{authorize(viewer,true);return ledger.get(viewer.getUUID(),activation).isPresent();}catch(SecurityException denied){return false;}}
    public boolean mayMoveInstance(ServerPlayer viewer,UUID activation,UUID instance,String hash){if(!mayManageInstance(viewer,activation))return false;var a=ledger.get(viewer.getUUID(),activation).orElseThrow();return a.instanceId().equals(instance)&&a.canonicalSha256().equals(hash)&&packages.worldLibrary().get(a.packageId()).filter(p->p.enabled()&&p.canonicalSha256().equals(hash)).isPresent();}
    public Map<String,Object> inspectMovement(ServerPlayer viewer,UUID activation)throws Exception{
        requireThread();var a=ledger.get(viewer.getUUID(),activation).orElseThrow();var i=instances.get(a.instanceId()).orElse(null);var p=packages.worldVersion(a.packageId(),a.canonicalSha256()).orElse(null);
        var value=new LinkedHashMap<String,Object>();value.put("activationId",a.operationId());value.put("instanceId",a.instanceId());value.put("activationRevision",a.revision());value.put("canonical",a.canonicalSha256());value.put("packageId",a.packageId());value.put("packageRevision",p==null?a.packageRevision():p.revision());value.put("packageName",p==null?a.packageId().toString():p.name());value.put("definitionId",a.definitionId());value.put("state",a.state());value.put("error",a.error());value.put("restoreEligible",WorldRestorePolicy.resumeEligible(a));value.put("location",i==null?a.location():i.location());value.put("instanceRevision",i==null?0:i.revision());value.put("movable",i!=null&&Set.of("ACTIVE","DISABLED").contains(a.state())&&mayMoveInstance(viewer,a.operationId(),a.instanceId(),a.canonicalSha256()));return Map.copyOf(value);
    }
    public Map<String,Object> movementPage(ServerPlayer viewer,int page)throws Exception{
        requireThread();var all=list(viewer.getUUID());int pages=Math.max(1,(all.size()+15)/16);if(page<0||page>=pages)throw new IllegalArgumentException("INSTANCE_PAGE_RANGE");
        var values=new ArrayList<Map<String,Object>>();for(var a:all.stream().skip(page*16L).limit(16).toList())values.add(inspectMovement(viewer,a.operationId()));return Map.of("page",page,"pages",pages,"count",all.size(),"instances",values);
    }
    public MoveResult move(ServerPlayer viewer,WorldInstanceMoveJournal.Input input,boolean confirmed)throws Exception{
        requireThread();authorize(viewer,confirmed);
        if(!viewer.getUUID().equals(input.owner()))throw new SecurityException("INSTANCE_MOVE_OWNER");
        var a=ledger.get(viewer.getUUID(),input.activation()).orElseThrow();
        if(!a.instanceId().equals(input.instance())||!a.canonicalSha256().equals(input.canonical()))throw new SecurityException("INSTANCE_MOVE_TARGET");
        var pack=packages.worldLibrary().get(a.packageId()).filter(p->p.enabled()&&p.canonicalSha256().equals(a.canonicalSha256())).orElseThrow(()->new IllegalStateException("INSTANCE_MOVE_CODE_CHANGED"));
        if(packages.ownedPackage(viewer.getUUID(),pack.packageId(),pack.revision()).isEmpty())throw new SecurityException("PACKAGE_NOT_OWNED");
        var before=instances.get(input.instance()).orElseThrow();var replay=moves.replay(input,true);
        if(replay.isPresent())return new MoveResult(replay.get(),before.location(),before.revision(),true);
        if(a.revision()!=input.activationRevision()||!before.location().equals(input.source()))throw new IllegalStateException("INSTANCE_MOVE_STALE");
        var h=hosts.get(input.instance());if(!Set.of("ACTIVE","DISABLED").contains(a.state())||a.state().equals("ACTIVE")&&(h==null||!h.current()))throw new IllegalStateException("INSTANCE_MOVE_NOT_READY");
        var targetLevel=level(before.location().dimension());var entities=new ArrayList<RuntimeObjectEntity>();
        for(var entry:before.state().entrySet())if(entry.getKey().startsWith("_object.")){
            var part=json.readValue(entry.getValue(),ObjectPart.class);var e=targetLevel.getEntity(part.entity());
            if(!part.state().equals("ACTIVE")||!(e instanceof RuntimeObjectEntity object)||!matches(object,part)||!object.header().instance().equals(before.instanceId())||!entry.getKey().equals("_object."+object.header().part()))throw new IllegalStateException("INSTANCE_MOVE_PART_CHANGED");
            objectBundle(object);entities.add(object);
        }
        var plan=WorldInstanceTranslation.prepare(targetLevel,before,input.target(),entities);
        var preparing=moves.prepare(input,before.revision(),true);if(!preparing.execute())throw new IllegalStateException("INSTANCE_MOVE_TICKET");
        try{
            if(h!=null)h.phase=Phase.MOVING;
            dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).worldUi().invalidateInstance(input.instance());
            plan.apply();
            var saved=instances.relocate(input.instance(),before.revision(),input.target(),plan.translatedState());if(!saved.accepted())throw new IllegalStateException("INSTANCE_MOVE_STATE_CONFLICT");
            if(h!=null){
                scripts.replaceBinding(input.instance(),h.packageRevision,"instance",saved.instance());h.phase=Phase.ACTIVE;
                scripts.fireTo(input.instance(),"instance.moved",new MoveEvent(input.operationId(),before,saved.instance()));
                if(!h.current()||!scripts.isLoaded(input.instance()))throw new IllegalStateException("INSTANCE_MOVE_HANDLER_FAILED");
            }
            var current=instances.get(input.instance()).orElseThrow();var receipt=moves.complete(input,current.revision());
            return new MoveResult(receipt,current.location(),current.revision(),false);
        }catch(Exception|LinkageError failure){
            try{moves.unknown(input,"INSTANCE_MOVE_UNKNOWN");}catch(Exception journalFailure){failure.addSuppressed(journalFailure);}
            if(h!=null){removeSharedHost(input.instance(),h);try{scripts.unload(input.instance());}catch(Exception unloadFailure){failure.addSuppressed(unloadFailure);}}
            if(Set.of("ACTIVE","RESTORE_PENDING","RESTORING").contains(a.state()))try{ledger.finish(a.operationId(),"INTERRUPTED","INSTANCE_MOVE_UNKNOWN");}catch(Exception ledgerFailure){failure.addSuppressed(ledgerFailure);}
            throw failure;
        }
    }
    public WorldActivationLedger.Activation activate(ServerPlayer viewer,UUID operation,UUID pkg,long revision,UUID definition,RuntimeInstanceLocation location,boolean confirmed)throws Exception{
        return activate(viewer,operation,pkg,revision,definition,location,confirmed,false);
    }
    public WorldActivationLedger.Activation activate(ServerPlayer viewer,UUID operation,UUID pkg,long revision,UUID definition,RuntimeInstanceLocation location,boolean confirmed,boolean autoRestore)throws Exception{
        requireThread();authorize(viewer,confirmed);
        var old=ledger.get(viewer.getUUID(),operation).orElse(null);
        if(old!=null)return ledger.prepare(viewer.getUUID(),operation,pkg,revision,old.canonicalSha256(),definition,location,true,autoRestore,viewer.nameAndId().name());
        var pack=packages.ownedPackage(viewer.getUUID(),pkg,revision).orElseThrow(()->new SecurityException("PACKAGE_NOT_OWNED"));
        String compatibility=packages.nativeCompatibility().check(pack,viewer.getUUID());if(!compatibility.isEmpty())throw new IllegalStateException(compatibility);
        if(pack.activationMode()!=ActivationMode.HOT_RUNTIME)throw new IllegalStateException("ACTIVATION_REQUIRES_LIFECYCLE");
        var plan=WorldContentPlan.resolve(pack,packages.worldContent());if(!plan.definitions().containsKey(definition))throw new IllegalArgumentException("WORLD_DEFINITION_REQUIRED");
        if(autoRestore&&!plan.supportsRestore(definition))throw new IllegalStateException("RESTORE_CONTRACT_MISSING");
        if(plan.supportsRestore(definition))verifyRegistration(plan,definition);
        var level=level(location.dimension());checkPosition(level,BlockPos.containing(location.x(),location.y(),location.z()));
        var activation=ledger.prepare(viewer.getUUID(),operation,pkg,revision,pack.canonicalSha256(),definition,location,true,autoRestore,viewer.nameAndId().name());
        long activationStarted=System.nanoTime();
        try{
            if(!pack.enabled()){var enabled=packages.worldLibrary().setEnabled(pkg,revision,true);if(!enabled.accepted())throw new IllegalStateException(enabled.errorCode());pack=enabled.runtimePackage();}
            var instance=instances.createIfAbsent(activation.instanceId(),pkg,definition,location,Map.of());
            var host=loadInstance(activation,pack,plan,instance,level,false);
            var result=ledger.finish(operation,"ACTIVE","");
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"WORLD_CONTENT_ACTIVATED",instance.instanceId().toString(),json.writeValueAsString(Map.of("packageId",pkg,"revision",pack.revision(),"operationId",operation,"verifiedBlocks",host.blockCount(),"mode","NATIVE_RHINO","activationMillis",(System.nanoTime()-activationStarted)/1_000_000)));
            return result;
        }catch(Exception|LinkageError e){
            removeSharedHost(activation.instanceId());try{scripts.unload(activation.instanceId());}catch(Exception ignored){}
            var diagnostic=scripts.failure(activation.instanceId());
            if(diagnostic.isPresent())MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"WORLD_SCRIPT_DIAGNOSTIC",activation.instanceId().toString(),json.writeValueAsString(diagnostic.get()));
            var result=ledger.finish(operation,"FAILED",diagnostic.map(ManagedScriptRuntime.Failure::code).orElse("WORLD_SCRIPT_FAILED"));
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("World script failed package={} instance={} code={}",pkg,activation.instanceId(),e.getClass().getSimpleName());return result;
        }
    }
    public WorldActivationLedger.Activation disable(ServerPlayer viewer,UUID operation)throws Exception{
        requireThread();var a=managedBy(viewer,operation);
        if(!Set.of("ACTIVE","PREPARING","RESTORE_PENDING","RESTORING").contains(a.state()))return a;
        removeSharedHost(a.instanceId());try{scripts.unload(a.instanceId());}catch(Exception e){return ledger.finish(operation,"FAILED","WORLD_UNLOAD_FAILED");}return ledger.finish(operation,"DISABLED","");
    }
    private void restoreViewer(ServerPlayer viewer){
        requireThread();if(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("RESTORE_SCOPE_DENIED");
    }
    private String resumeError(WorldActivationLedger.Activation a,RuntimePackage pack,RuntimeInstance instance){
        if(!WorldRestorePolicy.resumeEligible(a))return "RESTORE_NOT_ELIGIBLE";
        if(hosts.containsKey(a.instanceId())||scripts.isLoaded(a.instanceId()))return "RESTORE_HOST_PRESENT";
        if(moves.unresolved().stream().anyMatch(m->m.input().instance().equals(a.instanceId())))return "INSTANCE_MOVE_RECOVERY_REQUIRED";
        String error=WorldRestorePolicy.checkSource(a,pack,instance,ownerAllowed(a),moves.location(a.instanceId(),a.canonicalSha256(),a.location()));
        if(error.isEmpty())error=packages.nativeCompatibility().check(pack,a.owner());return error;
    }
    public Map<String,Object> inspectRestore(ServerPlayer viewer,UUID activation){
        restoreViewer(viewer);var a=ledger.get(viewer.getUUID(),activation).orElseThrow();var instance=instances.get(a.instanceId()).orElse(null);
        var pack=packages.worldLibrary().get(a.packageId()).filter(p->packages.ownedPackage(viewer.getUUID(),p.packageId(),p.revision()).isPresent()).orElse(null);
        String blocked=resumeError(a,pack,instance);boolean loaded=false;
        if(instance!=null){var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(instance.location().dimension())));var pos=BlockPos.containing(instance.location().x(),instance.location().y(),instance.location().z());loaded=level!=null&&level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4);}
        return Map.ofEntries(Map.entry("activationId",activation),Map.entry("instanceId",a.instanceId()),Map.entry("packageId",a.packageId()),Map.entry("packageName",pack==null?a.packageId().toString():pack.name()),Map.entry("packageRevision",pack==null?0:pack.revision()),Map.entry("activationRevision",a.revision()),Map.entry("instanceRevision",instance==null?0:instance.revision()),Map.entry("canonical",a.canonicalSha256()),Map.entry("environment",packages.nativeCompatibility().environmentHash()),Map.entry("state",a.state()),Map.entry("error",a.error()),Map.entry("restoreBlock",Objects.toString(a.restoreBlock(),"UNPROVEN")),Map.entry("autoRestore",a.autoRestore()),Map.entry("location",instance==null?a.location():instance.location()),Map.entry("eligible",WorldRestorePolicy.resumeEligible(a)),Map.entry("blocked",blocked),Map.entry("canResume",blocked.isEmpty()),Map.entry("chunkLoaded",loaded));
    }
    public WorldActivationLedger.ResumeInput authorizeResume(ServerPlayer viewer,UUID operation,Map<String,String> args){
        restoreViewer(viewer);if(!args.keySet().equals(Set.of("activationId","instanceId","canonical","activationRevision","packageRevision","instanceRevision","environment","confirmed"))||!"true".equals(args.get("confirmed")))throw new SecurityException("RESTORE_CONFIRM_REQUIRED");
        var a=ledger.get(viewer.getUUID(),UUID.fromString(args.get("activationId"))).orElseThrow();if(!ownerAllowed(a))throw new SecurityException("RESTORE_SCOPE_DENIED");
        var pack=packages.ownedPackage(viewer.getUUID(),a.packageId(),Long.parseLong(args.get("packageRevision"))).orElseThrow(()->new SecurityException("RESTORE_SCOPE_DENIED"));
        if(!a.instanceId().toString().equals(args.get("instanceId"))||!a.canonicalSha256().equals(args.get("canonical"))||!pack.canonicalSha256().equals(a.canonicalSha256()))throw new IllegalStateException("RESTORE_REQUEST_STALE");
        if(!packages.nativeCompatibility().environmentHash().equals(args.get("environment")))throw new IllegalStateException("RESTORE_ENVIRONMENT_CHANGED");
        String compatibility=packages.nativeCompatibility().check(pack,a.owner());if(!compatibility.isEmpty())throw new IllegalStateException(compatibility);
        var config=MineAgentRuntimeServices.config(server);
        return new WorldActivationLedger.ResumeInput(operation,viewer.getUUID(),a.operationId(),a.instanceId(),a.canonicalSha256(),Long.parseLong(args.get("activationRevision")),pack.revision(),Long.parseLong(args.get("instanceRevision")),args.get("environment"),config.permissionGeneration(a.owner(),PermissionAction.RUN_CODE),config.permissionGeneration(a.owner(),PermissionAction.MANAGE_PACKAGES));
    }
    public WorldActivationLedger.ResumeResult resume(ServerPlayer viewer,UUID operation,Map<String,String> args)throws Exception{
        var input=authorizeResume(viewer,operation,args);var replay=ledger.replayResume(input);if(replay.isPresent())return replay.get();
        var a=ledger.get(viewer.getUUID(),input.activation()).orElseThrow();var pack=packages.worldLibrary().get(a.packageId()).orElseThrow();var instance=instances.get(a.instanceId()).orElse(null);
        String error=resumeError(a,pack,instance);if(!error.isEmpty())throw new IllegalStateException(error);
        if(a.revision()!=input.activationRevision()||instance.revision()!=input.instanceRevision())throw new IllegalStateException("RESTORE_REQUEST_STALE");
        // Static source/registration checks only. Loading is queued behind a persistent single-attempt marker.
        try{var plan=WorldContentPlan.resolve(pack,packages.worldContent());verifyRegistration(plan,a.definitionId());}catch(dev.mineagent.runtime.scripting.ScriptRejectedException rejected){throw new IllegalStateException("RESTORE_REGISTRATION_REQUIRED");}catch(Exception unavailable){throw new IllegalStateException("RESTORE_PREPARATION_FAILED");}
        return ledger.resume(input,true);
    }
    private String pendingResumeError(WorldActivationLedger.Activation a,RuntimePackage pack,RuntimeInstance instance){
        var input=a.resumeInput();if(input==null)return "";var config=MineAgentRuntimeServices.config(server);
        if(config.permissionGeneration(a.owner(),PermissionAction.RUN_CODE)!=input.runGeneration()||config.permissionGeneration(a.owner(),PermissionAction.MANAGE_PACKAGES)!=input.manageGeneration())return "RESTORE_CONFIRMATION_EXPIRED";
        if(!packages.nativeCompatibility().environmentHash().equals(input.environment()))return "RESTORE_ENVIRONMENT_CHANGED";
        if(pack==null||pack.revision()!=input.packageRevision())return "RESTORE_SOURCE_CHANGED";
        if(instance==null||instance.revision()!=input.instanceRevision())return "RESTORE_INSTANCE_CHANGED";return "";
    }
    public WorldActivationLedger.Activation revokeRestore(ServerPlayer viewer,UUID operation,long revision)throws Exception{
        requireThread();var a=managedBy(viewer,operation);return ledger.revokeRestore(a.owner(),operation,revision);
    }
    private WorldActivationLedger.Activation managedBy(ServerPlayer viewer,UUID operation){
        var a=ledger.all().stream().filter(r->r.operationId().equals(operation)).findFirst().orElseThrow();
        if(!a.owner().equals(viewer.getUUID())&&!viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("ACTIVATION_OWNER");
        return a;
    }
    public static void verifyRegistration(WorldContentPlan plan,UUID definition){
        var checker=new dev.mineagent.runtime.scripting.preflight.RegistrationPreflight();
        String entry=plan.restoreEntrypoints().get(definition);if(entry==null)throw new IllegalStateException("RESTORE_CONTRACT_MISSING");
        for(var module:plan.modules().entrySet()){
            var result=module.getKey().equals(entry)?checker.lifecycle(module.getValue()):checker.inspect(module.getValue());
            if(!result.accepted())throw new dev.mineagent.runtime.scripting.ScriptRejectedException(result);
        }
    }
    private InstanceHost loadInstance(WorldActivationLedger.Activation a,RuntimePackage pack,WorldContentPlan plan,RuntimeInstance instance,ServerLevel level,boolean restoring)throws Exception{
        String compatibility=packages.nativeCompatibility().check(pack,a.owner());if(!compatibility.isEmpty())throw new IllegalStateException(compatibility);
        boolean registered=plan.supportsRestore(a.definitionId());
        var host=new InstanceHost(a,pack.revision(),level,registered?Phase.REGISTERING:Phase.LEGACY_LOAD);hosts.put(instance.instanceId(),host);
        var bindings=new LinkedHashMap<String,Object>(Map.of("server",server,"level",level,"instance",instance,"content",host));
        bindings.putAll(dev.mineagent.runtime.neoforge.ui.FeedbackRestartSmokeServer.bindings(server));
        scripts.load(instance.instanceId(),pack.revision(),plan.modules(),pack.entrypoints().get(plan.definitions().get(a.definitionId()).entrypointId()).path(),bindings);
        if(registered){host.phase=restoring?Phase.RESTORING:Phase.CREATING;if(!scripts.fireLifecycle(instance.instanceId(),restoring?"instance.restore":"instance.create",instance,java.time.Duration.ofMillis(200)))throw new IllegalStateException("LIFECYCLE_CALLBACK_FAILED");}
        host.phase=Phase.ACTIVE;return host;
    }
    private boolean ownerAllowed(WorldActivationLedger.Activation a){
        var online=server.getPlayerList().getPlayer(a.owner());
        boolean operator=online!=null?online.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER):!a.ownerName().isBlank()&&server.getProfilePermissions(new net.minecraft.server.players.NameAndId(a.owner(),a.ownerName())).hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var policy=MineAgentRuntimeServices.permissions(server);
        return policy.allowed(a.owner(),operator,PermissionAction.RUN_CODE)&&policy.allowed(a.owner(),operator,PermissionAction.MANAGE_PACKAGES);
    }
    private void restorePending()throws Exception{
        var pending=ledger.all().stream().filter(a->a.state().equals("RESTORE_PENDING")).toList();if(pending.isEmpty())return;
        for(int n=0;n<Math.min(8,pending.size());n++){
            var a=pending.get(Math.floorMod(restoreCursor++,pending.size()));var pack=packages.worldLibrary().get(a.packageId()).orElse(null);var instance=instances.get(a.instanceId()).orElse(null);
            var restoreLocation=moves.location(a.instanceId(),a.canonicalSha256(),a.location());
            String error=WorldRestorePolicy.check(a,pack,instance,ownerAllowed(a),restoreLocation);
            if(error.isEmpty())error=packages.nativeCompatibility().check(pack,a.owner());
            if(error.isEmpty())error=pendingResumeError(a,pack,instance);
            if(error.isEmpty()&&moves.unresolved().stream().anyMatch(m->m.input().instance().equals(a.instanceId())))error="INSTANCE_MOVE_RECOVERY_REQUIRED";
            if(!error.isEmpty()){ledger.interruptRestorable(a.operationId(),error,"BEFORE_RESTORE");continue;}
            var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(a.location().dimension())));
            if(level==null){ledger.interruptRestorable(a.operationId(),"RESTORE_DIMENSION_MISSING","BEFORE_RESTORE");continue;}
            var pos=BlockPos.containing(restoreLocation.x(),restoreLocation.y(),restoreLocation.z());
            if(!level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4))continue;
            try{
                checkPosition(level,pos);if(!objectsLoaded(instance,level))continue;var plan=WorldContentPlan.resolve(pack,packages.worldContent());verifyRegistration(plan,a.definitionId());
                var ticket=ledger.beginRestore(a.operationId());
                var host=loadInstance(ticket,pack,plan,instance,level,true);
                ledger.finish(a.operationId(),"ACTIVE","");
                MineAgentRuntimeServices.audit(server).record(a.owner().toString(),"WORLD_CONTENT_RESTORED",a.instanceId().toString(),json.writeValueAsString(Map.of("operationId",a.operationId(),"packageId",a.packageId(),"canonicalSha256",a.canonicalSha256(),"verifiedBlocks",host.blockCount(),"mode","REGISTERED_INSTANCE_RESTORE")));
            }catch(Exception|LinkageError failure){
                removeSharedHost(a.instanceId());try{scripts.unload(a.instanceId());}catch(Exception ignored){}
                var diagnostic=scripts.failure(a.instanceId());if(diagnostic.isPresent())MineAgentRuntimeServices.audit(server).record(a.owner().toString(),"WORLD_SCRIPT_DIAGNOSTIC",a.instanceId().toString(),json.writeValueAsString(diagnostic.get()));
                String failureCode=failure instanceof dev.mineagent.runtime.scripting.ScriptRejectedException?"RESTORE_REGISTRATION_REQUIRED":"RESTORE_EXECUTION_FAILED";
                var current=ledger.get(a.owner(),a.operationId()).orElseThrow();
                if(current.state().equals("RESTORE_PENDING"))ledger.interruptRestorable(a.operationId(),failure instanceof dev.mineagent.runtime.scripting.ScriptRejectedException?"RESTORE_REGISTRATION_REQUIRED":"RESTORE_PREPARATION_FAILED","BEFORE_RESTORE");else ledger.finish(a.operationId(),"INTERRUPTED",failureCode);
            }
            break; // At most one module initialization/native restore callback per polling tick.
        }
    }
    private void tick(){
        requireThread();
        pendingObjectSpawns.values().removeIf(e->e.isRemoved()||((ServerLevel)e.level()).getEntity(e.getUUID())==e);
        for(var h:List.copyOf(hosts.values())){String error=h.currentError();if(!error.isEmpty())try{removeSharedHost(h.activation.instanceId());scripts.unload(h.activation.instanceId());if(NativeCompatibilityPolicy.ERROR_CODES.contains(error))ledger.interruptRestorable(h.activation.operationId(),error,"COMPATIBILITY_UNLOADED");else ledger.finish(h.activation.operationId(),"INTERRUPTED",error);}catch(Exception e){throw new IllegalStateException("WORLD_CONTENT_RECONCILE",e);}}
        scripts.tick(server.getTickCount());
        if(server.getTickCount()%20==0)try{restorePending();}catch(Exception failure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("World restore polling failed: {}",failure.getClass().getSimpleName());}
        scripts.fire("tick",server.getTickCount());scripts.fire("server.tick",server.getTickCount());
        for(var h:List.copyOf(hosts.values()))if(!scripts.isLoaded(h.activation.instanceId()))try{
            var diagnostic=scripts.failure(h.activation.instanceId());
            if(diagnostic.isPresent())MineAgentRuntimeServices.audit(server).record(h.activation.owner().toString(),"WORLD_SCRIPT_DIAGNOSTIC",h.activation.instanceId().toString(),json.writeValueAsString(diagnostic.get()));
            removeSharedHost(h.activation.instanceId());ledger.finish(h.activation.operationId(),"FAILED",diagnostic.map(ManagedScriptRuntime.Failure::code).orElse("WORLD_HANDLER_FAILED"));
        }catch(Exception e){throw new IllegalStateException("WORLD_CONTENT_RECONCILE",e);}
    }
    private void authorize(ServerPlayer viewer,boolean confirmed){
        boolean op=viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);var permissions=MineAgentRuntimeServices.permissions(server);
        if(!confirmed||!permissions.allowed(viewer.getUUID(),op,PermissionAction.RUN_CODE)||!permissions.allowed(viewer.getUUID(),op,PermissionAction.MANAGE_PACKAGES))throw new SecurityException("NATIVE_ACTIVATION_DENIED");
    }
    private ServerLevel level(String dimension){var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(dimension)));if(level==null)throw new IllegalArgumentException("WORLD_DIMENSION_MISSING");return level;}
    private void requireThread(){if(closed||!server.isSameThread())throw new IllegalStateException("WORLD_CONTENT_UNAVAILABLE");}
    private static void checkPosition(ServerLevel level,BlockPos pos){if(!level.isInWorldBounds(pos)||!level.getWorldBorder().isWithinBounds(pos)||!level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4))throw new IllegalArgumentException("WORLD_POSITION_UNAVAILABLE");}
    @Override public void close()throws Exception{closed=true;try{scripts.close();}finally{hosts.clear();objectAssets.clear();pendingObjectSpawns.clear();try{if(sharedState!=null)sharedState.close();}finally{try{instances.close();}finally{try{moves.close();}finally{ledger.close();}}}}}

    private InstanceHost removeSharedHost(UUID instance){var events=MineAgentRuntimeServices.eventsIfPresent(server);if(events!=null)events.invalidateObjectInstance(instance);var runtime=MineAgentRuntimeServices.sharedStatesIfPresent(server);if(runtime!=null)runtime.invalidateInstance(instance);return hosts.remove(instance);}
    private boolean removeSharedHost(UUID instance,InstanceHost expected){if(hosts.get(instance)!=expected)return false;removeSharedHost(instance);return true;}
    private dev.mineagent.runtime.core.shared.SharedStateStore shared()throws Exception{
        requireThread();if(sharedState==null)sharedState=dev.mineagent.runtime.core.shared.SharedStateStore.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server),(c,write,admin)->{
            var h=hosts.get(c.scope().instance());if(h==null||!server.isSameThread()||!h.current()||h.scriptEvent!=null&&!h.scriptEvent.permitted()||h.scriptSchedule!=null&&!h.scriptSchedule.permitted()||!h.activation.packageId().equals(c.scope().pack())||!h.activation.owner().equals(c.owner())||h.packageRevision!=c.packageRevision()||!h.activation.canonicalSha256().equals(c.canonical()))return false;
            var pack=packages.worldLibrary().get(h.activation.packageId()).orElseThrow();if(!pack.permissions().contains("state.shared"))return false;
            var principal=h.sharedPrincipal!=null?h.sharedPrincipal:h.uiPlayer;
            String actorKind=principal==null?"PACKAGE":principal instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer?"AGENT":"PLAYER";
            if(!c.actorKind().equals(actorKind)||!c.actor().equals(principal==null?h.sharedServiceActor():principal.getUUID())||principal!=null&&(!principal.isAlive()||principal.level()!=h.level))return false;
            // A UI actor, even the package owner, is not a schema administrator. ui.read never borrows the owner identity.
            return (!write||!h.uiReadOnly&&h.phase!=Phase.REGISTERING)&&(!admin||principal==null&&c.actorKind().equals("PACKAGE"));
        });return sharedState;
    }

    /** Convenience mutations are attributed; direct Java remains available and is not claimed to be sandboxed. */
    public final class InstanceHost {
        private final WorldActivationLedger.Activation activation;private final long packageRevision;private final ServerLevel level;
        private Phase phase;private final UUID eventEpoch=UUID.randomUUID();private ScriptEventPayload scriptEvent;private ScriptSchedulePayload scriptSchedule;
        private ServerPlayer uiPlayer;private RuntimeObjectEntity uiEntity;private boolean uiReadOnly;
        private ServerPlayer sharedPrincipal;private UUID sharedOperation;private dev.mineagent.runtime.core.shared.SharedStateStore.Provenance sharedOrigin=dev.mineagent.runtime.core.shared.SharedStateStore.Provenance.NONE;private boolean feedbackPlanning;
        private final dev.mineagent.runtime.neoforge.scripting.MineAgentScriptHost mutation;
        private InstanceHost(WorldActivationLedger.Activation a,long revision,ServerLevel level,Phase phase){activation=a;packageRevision=revision;this.level=level;this.phase=phase;mutation=new dev.mineagent.runtime.neoforge.scripting.MineAgentScriptHost(server,a.owner());}
        private boolean current(){return currentError().isEmpty();}
        private String currentError(){
            if(closed||phase==Phase.MOVING||hosts.get(activation.instanceId())!=this||!(phase==Phase.REGISTERING||phase==Phase.LEGACY_LOAD||scripts.isLoaded(activation.instanceId())))return "STALE_PACKAGE";
            if(!ownerAllowed(activation))return "NATIVE_PERMISSION_REVOKED";
            var pack=packages.worldLibrary().get(activation.packageId()).orElse(null);
            if(pack==null||!pack.enabled()||pack.revision()!=packageRevision||!pack.canonicalSha256().equals(activation.canonicalSha256()))return "STALE_PACKAGE";
            return packages.nativeCompatibility().check(pack,activation.owner());
        }
        private RuntimeInstance currentInstance(){requireThread();String error=currentError();if(!error.isEmpty())throw new IllegalStateException(error);if(scriptEvent!=null&&!scriptEvent.permitted())throw new SecurityException("SCRIPT_EVENT_AUTHORITY_CHANGED");if(scriptSchedule!=null&&!scriptSchedule.permitted())throw new SecurityException("SCHEDULE_SCRIPT_AUTHORITY_CHANGED");return instances.get(activation.instanceId()).orElseThrow();}
        public String state(String key){if(feedbackPlanning)throw new SecurityException("FEEDBACK_USE_SCOPED_SNAPSHOT");return currentInstance().state().getOrDefault(stateKey(key),"");}
        public void state(String key,String value)throws Exception{var current=currentInstance();writable();var state=new LinkedHashMap<>(current.state());state.put(stateKey(key),Objects.requireNonNull(value));save(current,state);}
        private dev.mineagent.runtime.core.shared.SharedStateStore.Context sharedContext(String namespace){
            if(feedbackPlanning)throw new SecurityException("FEEDBACK_USE_SCOPED_SNAPSHOT");
            currentInstance();var principal=sharedPrincipal!=null?sharedPrincipal:uiPlayer;
            return new dev.mineagent.runtime.core.shared.SharedStateStore.Context(new dev.mineagent.runtime.core.shared.SharedStateStore.Scope(MineAgentRuntimeServices.worldId(server),activation.packageId(),activation.instanceId(),namespace),activation.owner(),principal==null?sharedServiceActor():principal.getUUID(),packageRevision,activation.canonicalSha256(),principal==null?"PACKAGE":principal instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer?"AGENT":"PLAYER");
        }
        private UUID sharedServiceActor(){return UUID.nameUUIDFromBytes(("package-shared-service|"+MineAgentRuntimeServices.worldId(server)+"|"+activation.packageId()+"|"+activation.instanceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        private UUID sharedOperation(String namespace,String key){
            if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,64}"))throw new IllegalArgumentException("SHARED_OPERATION_KEY");
            return UUID.nameUUIDFromBytes(("package-shared|"+activation.instanceId()+"|"+namespace+"|"+(sharedOperation==null?activation.operationId():sharedOperation)+"|"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        public String sharedDefine(String namespace,String operationKey,long expectedRevision,String schema)throws Exception{var c=sharedContext(namespace);writable();long previous;try{previous=shared().schemaForRuntime(c.scope()).schemaVersion();}catch(IllegalStateException absent){previous=0;}var result=shared().define(c,sharedOperation(namespace,operationKey),expectedRevision,schema);if(result.status().equals("APPLIED")&&shared().schemaForRuntime(c.scope()).schemaVersion()!=previous){var runtime=MineAgentRuntimeServices.sharedStatesIfPresent(server);if(runtime!=null)runtime.invalidateSchema(activation.instanceId(),namespace);}return json.writeValueAsString(result);}
        public String sharedDescribe(String namespace)throws Exception{return json.writeValueAsString(shared().describe(sharedContext(namespace)));}
        public String sharedReceipt(String namespace,String operationKey)throws Exception{var c=sharedContext(namespace);return json.writeValueAsString(shared().receipt(c,sharedOperation(namespace,operationKey)));}
        public String sharedMigrationPlan(String namespace,long expectedRevision,String schema,String migration)throws Exception{return json.writeValueAsString(shared().planMigration(sharedContext(namespace),expectedRevision,schema,migration));}
        public String sharedMigrate(String namespace,String operationKey,long expectedRevision,String schema,String migration)throws Exception{
            var c=sharedContext(namespace);writable();long previous=shared().schemaForRuntime(c.scope()).schemaVersion();
            var result=shared().migrate(c,sharedOperation(namespace,operationKey),expectedRevision,schema,migration);
            if(result.status().equals("APPLIED")&&shared().schemaForRuntime(c.scope()).schemaVersion()!=previous){var runtime=MineAgentRuntimeServices.sharedStatesIfPresent(server);if(runtime!=null)runtime.invalidateSchema(activation.instanceId(),namespace);}
            return json.writeValueAsString(result);
        }
        public String sharedRead(String namespace)throws Exception{return json.writeValueAsString(shared().read(sharedContext(namespace)));}
        public String sharedTransact(String namespace,String operationKey,String transaction)throws Exception{var c=sharedContext(namespace);writable();return json.writeValueAsString(shared().transact(c,sharedOperation(namespace,operationKey),transaction,sharedOrigin));}
        public String sharedWatch(String namespace,long afterRevision)throws Exception{return json.writeValueAsString(shared().watch(sharedContext(namespace),afterRevision));}
        private void writable(){if(phase==Phase.REGISTERING)throw new IllegalStateException("REGISTRATION_WRITE_DENIED");if(uiReadOnly)throw new IllegalStateException("WORLD_UI_READ_ONLY");}
        public void openUi(ServerPlayer player,String entry)throws Exception{
            currentInstance();writable();if(player==null||player!=uiPlayer||uiEntity==null)throw new SecurityException("WORLD_UI_INTERACTION_REQUIRED");
            dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).worldUi().issue(player,activation.instanceId(),uiEntity.getUUID(),uiEntity.header().part(),entry);
        }
        private String stateKey(String key){if(key==null||!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("WORLD_STATE_KEY");return key;}
        private void save(RuntimeInstance current,Map<String,String> state)throws Exception{if(!instances.updateState(current.instanceId(),current.revision(),state).accepted())throw new IllegalStateException("WORLD_STATE_CONFLICT");}
        public boolean placeBlock(String part,int dx,int dy,int dz,String blockId)throws Exception{
            var current=currentInstance();writable();if(part==null||!part.matches("[A-Za-z0-9_.-]{1,64}")||Math.abs((long)dx)>32||Math.abs((long)dy)>32||Math.abs((long)dz)>32)throw new IllegalArgumentException("WORLD_PART_BOUNDS");
            LegacyContentBoundary.requireGenerativePrimitive(blockId);
            var pos=BlockPos.containing(current.location().x(),current.location().y(),current.location().z()).offset(dx,dy,dz);checkPosition(level,pos);
            String key="_block."+part;String value=json.writeValueAsString(Map.of("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"block",blockId));var old=current.state().get(key);
            if(old!=null){if(!json.readTree(old).equals(json.readTree(value)))throw new IllegalArgumentException("WORLD_PART_REUSED");if(!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(blockId))throw new IllegalStateException("WORLD_PART_CHANGED");return false;}
            if(phase==Phase.RESTORING)throw new IllegalStateException("RESTORE_CANNOT_CREATE_PART");
            if(current.state().keySet().stream().filter(k->k.startsWith("_block.")).count()>=128)throw new IllegalStateException("WORLD_PART_BUDGET");
            if(!level.getBlockState(pos).isAir())throw new IllegalStateException("WORLD_POSITION_OCCUPIED");
            if(!mutation.placeBlock(current.location().dimension(),pos.getX(),pos.getY(),pos.getZ(),blockId))throw new IllegalStateException("WORLD_BLOCK_NOT_PLACED");
            var state=new LinkedHashMap<>(current.state());state.put(key,value);save(current,state);return true;
        }
        public int blockCount(){var instance=currentInstance();int count=0;try{for(var e:instance.state().entrySet())if(e.getKey().startsWith("_block.")){var block=json.readTree(e.getValue());var pos=new BlockPos(block.path("x").asInt(),block.path("y").asInt(),block.path("z").asInt());if(level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4)&&net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(block.path("block").asText()))count++;}}catch(Exception e){throw new IllegalStateException("WORLD_BLOCK_RECORD",e);}return count;}
        public RuntimeObjectEntity createObject(String partKey,String modelPath,double dx,double dy,double dz)throws Exception{
            var instance=currentInstance();writable();if(partKey==null||!partKey.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("OBJECT_PART_KEY");for(double value:new double[]{dx,dy,dz})if(!Double.isFinite(value)||Math.abs(value)>32)throw new IllegalArgumentException("OBJECT_PART_POSITION");
            var pack=packages.worldLibrary().get(instance.packageId()).orElseThrow();var bundle=model(pack,pack.definitions().get(instance.definitionId()),modelPath);var old=part(instance,partKey);
            if(old!=null){if(!old.model().equals(modelPath)||!old.hash().equals(bundle.sha256())||old.dx()!=dx||old.dy()!=dy||old.dz()!=dz)throw new IllegalStateException("OBJECT_PART_REUSED");if(!old.state().equals("ACTIVE"))throw new IllegalStateException("OBJECT_OUTCOME_UNKNOWN");var e=level.getEntity(old.entity());if(!(e instanceof RuntimeObjectEntity object)||!matches(object,old))throw new IllegalStateException("OBJECT_NOT_LOADED");object.bind(instance.instanceId(),partKey,modelPath,bundle);return object;}
            if(phase==Phase.RESTORING)throw new IllegalStateException("RESTORE_CANNOT_CREATE_PART");
            if(instance.state().keySet().stream().filter(k->k.startsWith("_object.")).count()>=32)throw new IllegalStateException("OBJECT_PART_BUDGET");
            if(instances.all().stream().flatMap(i->i.state().keySet().stream()).filter(k->k.startsWith("_object.")).count()>=128)throw new IllegalStateException("OBJECT_WORLD_BUDGET");
            var entity=new RuntimeObjectEntity(dev.mineagent.runtime.neoforge.MineAgentRegistries.RUNTIME_OBJECT.get(),level);var id=UUID.nameUUIDFromBytes((instance.instanceId()+"|object|"+partKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));entity.setUUID(id);entity.bind(instance.instanceId(),partKey,modelPath,bundle);entity.setPos(instance.location().x()+dx,instance.location().y()+dy,instance.location().z()+dz);
            var bounds=entity.getBoundingBox();checkPosition(level,BlockPos.containing(bounds.minX,bounds.minY,bounds.minZ));checkPosition(level,BlockPos.containing(bounds.maxX,bounds.maxY,bounds.maxZ));
            if(!level.noCollision(entity)||level.getEntity(id)!=null||pendingObjectSpawns.containsKey(id)||pendingObjectSpawns.values().stream().anyMatch(e->!e.isRemoved()&&e.level()==level&&e.getBoundingBox().intersects(bounds)))throw new IllegalStateException("OBJECT_POSITION_OCCUPIED");
            var state=new LinkedHashMap<>(instance.state());state.put("_object."+partKey,json.writeValueAsString(new ObjectPart(id,modelPath,bundle.sha256(),dx,dy,dz,"PREPARING")));save(instance,state);
            pendingObjectSpawns.put(id,entity); // Only after the durable intent. A thrown/unknown spawn retains its reservation.
            if(!level.addFreshEntity(entity)){pendingObjectSpawns.remove(id,entity);throw new IllegalStateException("OBJECT_SPAWN_FAILED");}
            instance=currentInstance();state=new LinkedHashMap<>(instance.state());state.put("_object."+partKey,json.writeValueAsString(new ObjectPart(id,modelPath,bundle.sha256(),dx,dy,dz,"ACTIVE")));save(instance,state);return entity;
        }
        public RuntimeObjectEntity object(String partKey)throws Exception{var instance=currentInstance();var p=part(instance,partKey);if(p==null||!p.state().equals("ACTIVE")||!(level.getEntity(p.entity()) instanceof RuntimeObjectEntity e)||!matches(e,p))throw new IllegalStateException("OBJECT_NOT_LOADED");return e;}
        public int objectCount(){var instance=currentInstance();int count=0;try{for(var entry:instance.state().entrySet())if(entry.getKey().startsWith("_object.")){var p=json.readValue(entry.getValue(),ObjectPart.class);if(p.state().equals("ACTIVE")&&level.getEntity(p.entity()) instanceof RuntimeObjectEntity e&&matches(e,p)&&e.isAlive()){objectBundle(e);count++;}}}catch(Exception e){throw new IllegalStateException("OBJECT_RECORD_INVALID",e);}return count;}
    }
}
