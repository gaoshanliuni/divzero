package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.events.RuntimeEventStore;
import dev.mineagent.runtime.core.shared.*;
import dev.mineagent.runtime.core.task.TaskResultFence;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit owner-authorized data access by the real Agent, never by a borrowed viewer or package-service identity. */
public final class NativeSharedStateRuntime implements AutoCloseable {
    private final MinecraftServer server;private final UUID epoch=UUID.randomUUID();private final SharedStateStore store;private final SharedEventBridge bridge;private final ObjectMapper json=new ObjectMapper();
    private final SharedResourceEpochs resourceEpochs=new SharedResourceEpochs();
    private volatile boolean closed;private Access active;private String diagnostic="READY";private long exported,expiredRecords;private boolean expiryBacklog;
    private record Access(SharedStateStore.Context context,UUID owner,UUID agent,SharedStateTarget target,long accessEpoch,long directoryEpoch,ManagedTask task,MineAgentPlayer body){}
    public NativeSharedStateRuntime(MinecraftServer server){this.server=server;try{store=SharedStateStore.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server),this::authorize);bridge=new SharedEventBridge(store,e->MineAgentRuntimeServices.events(server).ingestDurable(e));}catch(Exception e){throw new IllegalStateException("SHARED_AGENT_STORE",e);}}
    public long epoch(UUID owner){return MineAgentRuntimeServices.permissions(server).actionRevision(owner,PermissionAction.ACCESS_SHARED_STATE);}
    private long directoryEpoch(UUID owner){return MineAgentRuntimeServices.permissions(server).actionRevision(owner,PermissionAction.DISCOVER_OBJECTS);}
    public boolean sameEpoch(UUID owner,long value){return !closed&&epoch(owner)==value;}
    public boolean granted(UUID owner){var a=MineAgentRuntimeServices.permissions(server).trustedActions(owner);return a.contains(PermissionAction.ACCESS_SHARED_STATE)&&a.contains(PermissionAction.DISCOVER_OBJECTS);}
    private dev.mineagent.runtime.api.agent.AgentDefinition definition(UUID agent){return MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElse(null);}
    private WorldContentRuntime.SharedDescriptor resource(UUID owner,UUID agent,SharedStateTarget target){
        if(closed||!server.isSameThread()||!granted(owner))throw new SecurityException("SHARED_AGENT_PERMISSION_REQUIRED");var d=definition(agent);var body=MineAgentRuntimeServices.bodies(server).body(agent).orElse(null);
        if(d==null||!MineAgentRuntimeServices.permissions(server).canMutateAgent(d,owner,false)||body==null||!body.isAlive())throw new SecurityException("SHARED_AGENT_AUTHORITY_REQUIRED");
        var r=WorldContentRuntime.get(server).sharedDescriptor(target.instanceId());if(!r.owner().equals(owner)||!r.packageId().equals(target.packageId())||r.packageRevision()!=target.packageRevision()||!r.canonicalSha256().equals(target.canonicalSha256()))throw new SecurityException("SHARED_RESOURCE_UNOWNED_OR_CHANGED");return r;
    }
    private boolean authorize(SharedStateStore.Context c,boolean write,boolean admin){
        Access a=active;if(a==null||a.context()!=c||admin||closed||!server.isSameThread()||a.accessEpoch()!=epoch(a.owner())||a.directoryEpoch()!=directoryEpoch(a.owner()))return false;
        try{resource(a.owner(),a.agent(),a.target());if(MineAgentRuntimeServices.bodies(server).body(a.agent()).orElse(null)!=a.body())return false;
            if(a.task()==null)return !write;
            var live=MineAgentRuntimeServices.tasks(server).get(a.task().taskId()).orElse(null);return TaskResultFence.current(a.task(),live)&&live.status()==TaskStatus.RUNNING&&MineAgentRuntimeServices.taskExecutor(server).authorized(live);
        }catch(Exception denied){return false;}
    }
    @FunctionalInterface private interface Read<T>{T run(SharedStateStore.Context context)throws Exception;}
    private <T>T access(UUID owner,UUID agent,SharedStateTarget target,ManagedTask task,Read<T> action)throws Exception{
        var r=resource(owner,agent,target);if(active!=null)throw new IllegalStateException("SHARED_AGENT_REENTRANT_ACCESS");var c=new SharedStateStore.Context(target.scope(MineAgentRuntimeServices.worldId(server)),r.owner(),agent,r.packageRevision(),r.canonicalSha256(),"AGENT");
        active=new Access(c,owner,agent,target,epoch(owner),directoryEpoch(owner),task,MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow());try{return action.run(c);}finally{active=null;}
    }
    public long creationRevision(ManagedTask task,SharedSubscriptionFilter filter)throws Exception{return access(task.ownerPlayerId(),task.agentId(),filter.target(),task,c->{requireFilter(c,filter);return store.describe(c).revision();});}
    public long managementRevision(UUID owner,UUID agent,SharedSubscriptionFilter filter)throws Exception{return access(owner,agent,filter.target(),null,c->{requireFilter(c,filter);return store.describe(c).revision();});}
    private void requireFilter(SharedStateStore.Context c,SharedSubscriptionFilter filter)throws Exception{
        var schema=store.schemaForRuntime(c.scope());if(schema.schemaVersion()!=filter.schemaVersion()||!schema.owner().equals(c.owner()))throw new SecurityException("SHARED_SCHEMA_CHANGED");for(String key:filter.keys()){var f=schema.fields().get(key);if(f==null||!f.canRead(c))throw new SecurityException("SHARED_KEYS_UNAVAILABLE");}for(var condition:filter.when())if(Set.of("LT","LTE","GT","GTE").contains(condition.test())&&(!Set.of("INTEGER","NUMBER").contains(schema.fields().get(condition.key()).type())||condition.value()==null||!condition.value().isNumber()))throw new IllegalArgumentException("SHARED_CONDITION_SCHEMA");
    }
    /** Standing metadata authority only; no task execution or snapshot values are read inside this token check. */
    public String standingToken(UUID owner,UUID agent,SharedSubscriptionFilter filter){try{
        var r=resource(owner,agent,filter.target());var c=new SharedStateStore.Context(filter.target().scope(MineAgentRuntimeServices.worldId(server)),r.owner(),agent,r.packageRevision(),r.canonicalSha256(),"AGENT");requireFilter(c,filter);var d=definition(agent);
        return epoch+"|"+epoch(owner)+"|"+directoryEpoch(owner)+"|"+r.activationId()+"|"+r.packageRevision()+"|"+r.canonicalSha256()+"|"+filter.schemaVersion()+"|"+d.ownerPlayerId()+"|"+d.collaboratorPlayerIds().stream().map(UUID::toString).sorted().toList();
    }catch(Exception denied){return null;}}
    public boolean waitingForResource(UUID owner,UUID agent,SharedSubscriptionFilter filter){try{
        if(closed||!server.isSameThread()||!granted(owner))return false;var definition=definition(agent);if(definition==null||!MineAgentRuntimeServices.permissions(server).canMutateAgent(definition,owner,false))return false;
        var target=filter.target();if(!WorldContentRuntime.get(server).instanceEventWaiting(owner,target.packageId(),target.instanceId(),target.packageRevision(),target.canonicalSha256()))return false;
        var context=new SharedStateStore.Context(target.scope(MineAgentRuntimeServices.worldId(server)),owner,agent,target.packageRevision(),target.canonicalSha256(),"AGENT");requireFilter(context,filter);return true;
    }catch(Exception unavailable){return false;}}
    /** The destination already has a valid World UI grant; evaluate only key visibility, never read cross-actor values. */
    public boolean visiblePushChange(dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch launch,RuntimeEventStore.Event event)throws Exception{
        if(event.shared()==null)return true;var target=event.shared().target();if(!target.packageId().equals(launch.packageId())||!target.instanceId().equals(launch.instanceId())||target.packageRevision()!=launch.packageRevision()||!target.canonicalSha256().equals(launch.canonicalSha256()))return false;
        var scope=target.scope(launch.worldId());var schema=store.schemaForRuntime(scope);var context=new SharedStateStore.Context(scope,schema.owner(),launch.actorId(),launch.packageRevision(),launch.canonicalSha256(),launch.actorKind().name());
        for(var change:event.shared().changes()){var field=schema.fields().get(change.key());if(field!=null&&field.canRead(context)&&(event.shared().schema()||field.subject(context).equals(change.subject())))return true;}return event.shared().schema();
    }
    public Set<String> visibleChangedKeys(UUID owner,UUID agent,SharedSubscriptionFilter filter,RuntimeEventStore.Event event)throws Exception{
        if(event.shared()==null||!event.shared().target().equals(filter.target())||event.shared().revision()<=filter.afterRevision())return Set.of();
        var r=resource(owner,agent,filter.target());var c=new SharedStateStore.Context(filter.target().scope(event.world()),r.owner(),agent,r.packageRevision(),r.canonicalSha256(),"AGENT");requireFilter(c,filter);var schema=store.schemaForRuntime(c.scope());var keys=new TreeSet<String>();
        for(var changed:event.shared().changes()){var f=schema.fields().get(changed.key());if(filter.keys().contains(changed.key())&&f!=null&&f.canRead(c)&&(event.shared().schema()||f.subject(c).equals(changed.subject())))keys.add(changed.key());}return Set.copyOf(keys);
    }
    public record EventEvaluation(boolean matched,Map<String,String> observation){public EventEvaluation{observation=Map.copyOf(observation);}}
    /** Called before the event-store transaction. Predicate and receipt use exactly the same authorized snapshot. */
    public EventEvaluation evaluateEvent(UUID owner,UUID agent,SharedSubscriptionFilter filter,RuntimeEventStore.Event event)throws Exception{
        if(visibleChangedKeys(owner,agent,filter,event).isEmpty())return new EventEvaluation(false,Map.of());
        return access(owner,agent,filter.target(),null,c->{var value=store.read(c,filter.keys());
            if(value.schemaVersion()!=filter.schemaVersion())throw new SecurityException("SHARED_SCHEMA_CHANGED");boolean matched=filter.matches(value);
            return new EventEvaluation(matched,Map.of("evaluatedRevision",Long.toString(value.revision()),"schemaVersion",Long.toString(value.schemaVersion()),"predicateMatched",Boolean.toString(matched),"evaluationMode","AUTHORIZED_TTL_FRESH_SNAPSHOT_BEFORE_INGEST_NOT_HISTORICAL_VALUE"));
        });
    }
    public Map<String,Object> sharedEventForModel(UUID owner,UUID agent,SharedSubscriptionFilter filter,RuntimeEventStore.Event event)throws Exception{
        var keys=visibleChangedKeys(owner,agent,filter,event);var head=access(owner,agent,filter.target(),null,c->store.describe(c));
        return Map.of("eventId",event.id(),"source",event.source(),"target",filter.target().wire(),"stateRevision",event.shared().revision(),"currentRevision",head.revision(),"changedReadableKeys",keys,"snapshotRequired",true,"occurredAt",event.occurredAt(),"occurredAtKnown",event.occurredAt()>0,"authorOmitted",true);
    }
    private Object list(ManagedTask task,SharedStateToolRequest request)throws Exception{
        resource(task.ownerPlayerId(),task.agentId(),request.target());var names=store.namespacesForRuntime(request.target().packageId(),request.target().instanceId());var result=new ArrayList<Object>();int index=request.offset();
        while(index<names.size()&&result.size()<16){var t=new SharedStateTarget(request.target().packageId(),request.target().instanceId(),request.target().packageRevision(),request.target().canonicalSha256(),names.get(index));var d=access(task.ownerPlayerId(),task.agentId(),t,task,c->store.describe(c));
            var fields=d.fields().entrySet().stream().map(e->Map.of("key",e.getKey(),"type",e.getValue().type(),"scope",e.getValue().scope(),"write",e.getValue().write(),"ttlSeconds",e.getValue().ttlSeconds())).toList();var value=Map.of("namespace",names.get(index),"revision",d.revision(),"schemaVersion",d.schemaVersion(),"fields",fields);result.add(value);if(json.writeValueAsBytes(result).length>12000){result.removeLast();break;}index++;
        }
        if(result.isEmpty()&&index<names.size())throw new IllegalStateException("SHARED_METADATA_BUDGET");return Map.of("namespaces",result,"offset",request.offset(),"nextOffset",index,"more",index<names.size());
    }
    public Map<String,String> execute(ManagedTask task,UUID operation,String tool,String arguments)throws Exception{
        var request=SharedStateToolRequest.parse(tool,arguments);Object result;long schema=0;
        if(tool.equals("list_shared_namespaces"))result=list(task,request);
        else{result=access(task.ownerPlayerId(),task.agentId(),request.target(),task,c->switch(tool){
            case "read_shared_state"->store.read(c,request.keys());
            case "watch_shared_state"->store.watch(c,request.cursor());
            case "transact_shared_state"->store.transact(c,operation,request.transaction(),new SharedStateStore.Provenance(task.taskId(),task.intentRevision(),MineAgentRuntimeServices.events(server).causalChain(task)));
            default->throw new IllegalArgumentException("SHARED_TOOL");});schema=store.schemaForRuntime(request.target().scope(task.worldId())).schemaVersion();}
        String text=json.writeValueAsString(result);if(text.getBytes(StandardCharsets.UTF_8).length>16000)throw new IllegalArgumentException("SHARED_RESULT_BUDGET_SELECT_FEWER_KEYS");
        return Map.of("executionMode","NATIVE_AGENT_SHARED_STATE","result",text,"request",request.canonical(),"serverEpoch",epoch.toString(),"accessRevision",Long.toString(epoch(task.ownerPlayerId())),"schemaVersion",Long.toString(schema),"deliveryVerified","false");
    }
    public Map<String,String> forModel(ManagedTask task,String tool,Map<String,String> after){if(!SharedStateToolRequest.TOOLS.contains(tool))return after;try{
        if(!epoch.toString().equals(after.get("serverEpoch"))||!Long.toString(epoch(task.ownerPlayerId())).equals(after.get("accessRevision")))throw new IllegalStateException();var request=SharedStateToolRequest.parse(tool,after.get("request"));resource(task.ownerPlayerId(),task.agentId(),request.target());
        if(tool.equals("list_shared_namespaces")){if(!json.readTree(after.get("result")).equals(json.valueToTree(list(task,request))))throw new IllegalStateException();}
        else{var description=access(task.ownerPlayerId(),task.agentId(),request.target(),task,c->store.describe(c));if(description.schemaVersion()!=Long.parseLong(after.get("schemaVersion")))throw new IllegalStateException();if(!tool.equals("transact_shared_state")&&json.readTree(after.get("result")).path("revision").asLong()!=access(task.ownerPlayerId(),task.agentId(),request.target(),task,c->store.read(c)).revision())throw new IllegalStateException();}
        return after;
    }catch(Exception invalid){return Map.of("executionMode","SHARED_OBSERVATION_REDACTED","reason","CURRENT_SHARED_AUTHORITY_AND_STATE_REQUIRED");}}
    public void invalidateInstance(UUID instance){resourceEpochs.invalidateInstance(instance);}
    public void invalidateSchema(UUID instance,String namespace){resourceEpochs.invalidateSchema(instance,namespace);}
    public java.util.function.BooleanSupplier workerTargetPermit(UUID owner,UUID agent,SharedStateTarget target){
        resource(owner,agent,target);var local=resourceEpochs.capture(target.instanceId(),target.namespace());long captured=epoch(owner);var library=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).worldLibrary();
        return ()->!closed&&sameEpoch(owner,captured)&&local.getAsBoolean()&&library.get(target.packageId()).filter(p->p.enabled()&&p.revision()==target.packageRevision()&&p.canonicalSha256().equals(target.canonicalSha256())).isPresent();
    }
    public java.util.function.BooleanSupplier workerPermit(UUID owner,UUID agent,SharedSubscriptionFilter filter){
        if(standingToken(owner,agent,filter)==null)return ()->false;var local=resourceEpochs.capture(filter.target().instanceId(),filter.target().namespace());long captured=epoch(owner);var library=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).worldLibrary();
        return ()->!closed&&sameEpoch(owner,captured)&&local.getAsBoolean()&&library.get(filter.target().packageId()).filter(p->p.enabled()&&p.revision()==filter.target().packageRevision()&&p.canonicalSha256().equals(filter.target().canonicalSha256())).isPresent();
    }
    public void tick(){
        if(closed||server.getTickCount()%20!=0)return;
        boolean expiryFailed=false,exportFailed=false;
        try{var sweep=store.expireDue(4,scope->WorldContentRuntime.sharedExpiryBinding(server,scope));expiredRecords+=sweep.records();expiryBacklog=sweep.more();}
        catch(Exception failure){expiryFailed=true;expiryBacklog=true;MineAgentRuntimeMod.LOGGER.warn("Shared expiry paused code={}",failure.getClass().getSimpleName());}
        // Export prior commits even when cleanup is blocked; neither path deletes operation receipts to make room.
        try{exported+=bridge.forward(16);}catch(Exception failure){exportFailed=true;MineAgentRuntimeMod.LOGGER.warn("Shared event export paused code={}",failure.getClass().getSimpleName());}
        diagnostic=expiryFailed?(exportFailed?"SHARED_EXPIRY_AND_EXPORT_BACKPRESSURE":"SHARED_EXPIRY_BACKPRESSURE"):(exportFailed?"SHARED_EXPORT_BACKPRESSURE":"READY");
    }
    public Map<String,Object> diagnostics(){return Map.of("exported",exported,"status",diagnostic,"expiredRecordsFromCompletedSweeps",expiredRecords,"expiryBacklog",expiryBacklog,"expiryNamespaceBudgetPerSweep",4,"producerGuarantee","DURABLE_SHARED_COMMIT_OUTBOX_AT_LEAST_ONCE");}
    @Override public void close()throws Exception{closed=true;resourceEpochs.close();active=null;store.close();}
}
