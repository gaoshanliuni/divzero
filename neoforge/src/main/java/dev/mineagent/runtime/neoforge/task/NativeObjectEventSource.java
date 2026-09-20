package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.directory.*;
import dev.mineagent.runtime.core.events.*;
import dev.mineagent.runtime.core.shared.SharedStateStore;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.content.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Trusted Native callback capture; it never executes a package handler, model, or world mutation on retry. */
public final class NativeObjectEventSource implements AutoCloseable {
    public record Capture(UUID id,ObjectInteractionFilter.Target target,UUID owner,UUID activation,ObjectQuery.Point objectPosition,ObjectDirectory.Entry actor,String actorKind,boolean originKnown,SharedStateStore.Provenance origin,Map<UUID,ObjectInteractionFilter.Recipient> subscriptions,long occurredAt){public Capture{subscriptions=Map.copyOf(subscriptions);}}
    private static final class Pending{final RuntimeEventStore.Event event;int failures;Pending(RuntimeEventStore.Event event){this.event=event;}}
    private record LeaseKey(UUID owner,ObjectInteractionFilter.Target target){}
    private record ResourceLease(String token,RuntimeObjectEntity object,AtomicBoolean valid,BooleanSupplier permit){}
    private final MinecraftServer server;private final RuntimeEventStore store;private final Function<RuntimeEventStore.Subscription,String> authority;private final Supplier<NeoForgeObjectDirectory> directory;
    private final UUID sourceEpoch=UUID.randomUUID();private final ArrayDeque<Pending> queue=new ArrayDeque<>();private final Map<LeaseKey,ResourceLease> leases=new HashMap<>();private final AtomicLong failureEpoch=new AtomicLong();private final Map<UUID,AtomicLong> instanceEpochs=new HashMap<>();
    private final ObjectMapper json=new ObjectMapper();private volatile boolean closed;private String failure="";private int captureTick=-1,captures;private long committed,unknownAgentWakeSuppressed;
    NativeObjectEventSource(MinecraftServer server,RuntimeEventStore store,Function<RuntimeEventStore.Subscription,String> authority,Supplier<NeoForgeObjectDirectory> directory){this.server=server;this.store=store;this.authority=authority;this.directory=directory;}
    private void thread(){if(closed||!server.isSameThread())throw new IllegalStateException("OBJECT_EVENT_THREAD");}
    public String token(UUID owner,ObjectInteractionFilter.Target target){try{
        thread();if(!failure.isEmpty())return null;var value=WorldContentRuntime.get(server).objectEventTarget(owner,target);var generation=directory.get().observeNativeManagedObject(value.object()).ref().generation();
        if(instanceEpochs.size()>=4096&&!instanceEpochs.containsKey(target.instanceId()))throw new IllegalStateException("OBJECT_EVENT_EPOCH_BUDGET");long generationEpoch=instanceEpochs.computeIfAbsent(target.instanceId(),id->new AtomicLong()).get();
        var permissions=MineAgentRuntimeServices.permissions(server);return generationEpoch+"|"+sourceEpoch+"|"+value.activationId()+"|"+generation+"|"+permissions.actionRevision(owner,PermissionAction.RUN_CODE)+"|"+permissions.actionRevision(owner,PermissionAction.MANAGE_PACKAGES);
    }catch(RuntimeException unavailable){return null;}}
    public void require(UUID owner,ObjectInteractionFilter.Target target){if(token(owner,target)==null)throw new SecurityException("OBJECT_EVENT_RESOURCE_UNAVAILABLE");}
    public Map<String,Object> inspect(UUID owner,UUID instance,int offset)throws Exception{
        thread();var targets=WorldContentRuntime.get(server).objectEventTargets(owner,instance);var items=new ArrayList<Object>();int cursor=offset;
        while(cursor<targets.size()&&items.size()<8){var descriptor=targets.get(cursor);var item=new LinkedHashMap<String,Object>(descriptor.target().wire());item.put("activation_id",descriptor.activationId());item.put("available",descriptor.object()!=null&&token(owner,descriptor.target())!=null);items.add(item);if(json.writeValueAsBytes(items).length>12000){items.removeLast();break;}cursor++;}
        if(items.isEmpty()&&cursor<targets.size())throw new IllegalStateException("OBJECT_EVENT_TARGET_PAGE_BUDGET");return Map.of("instance_id",instance,"targets",items,"offset",offset,"nextOffset",cursor,"more",cursor<targets.size(),"discoveryOnly",true,"sourceStatus",failure.isEmpty()?"READY":failure);
    }
    public Capture begin(RuntimeObjectEntity object,ServerPlayer actor,UUID operation,boolean originKnown,SharedStateStore.Provenance origin){
        try{
            thread();if(!failure.isEmpty()||!actor.isAlive()||actor.isRemoved())return null;var subscriptions=store.subscriptionsForRuntime().stream().filter(s->s.state().equals("ACTIVE")&&s.request().object()!=null).toList();if(subscriptions.isEmpty())return null;
            if(captureTick!=server.getTickCount()){captureTick=server.getTickCount();captures=0;}if(++captures>64||queue.size()>=256){fail("OBJECT_EVENT_CAPTURE_BACKPRESSURE");return null;}
            var descriptor=WorldContentRuntime.get(server).objectEventTarget(object);String kind=actor instanceof MineAgentPlayer?"AGENT":"PLAYER";var recipients=new TreeMap<UUID,ObjectInteractionFilter.Recipient>();long now=System.currentTimeMillis();
            for(var sub:subscriptions){var filter=sub.request().object();if(!filter.target().equals(descriptor.target())||!sub.creator().scope().ownerId().equals(descriptor.owner())||!filter.selects(actor.getUUID(),kind)||now>=sub.expiresAt())continue;String grant=authority.apply(sub);if(grant==null)continue;
                if(!sub.request().mode().equals("RECORD_ONLY")&&!originKnown){unknownAgentWakeSuppressed++;continue;}recipients.put(sub.id(),new ObjectInteractionFilter.Recipient(sub.revision(),hash(grant)));}
            if(recipients.isEmpty())return null;
            return new Capture(operation,descriptor.target(),descriptor.owner(),descriptor.activationId(),new ObjectQuery.Point(object.level().dimension().identifier().toString(),object.getX(),object.getY(),object.getZ()),directory.get().observeNativeInteractionActor(actor),kind,originKnown,origin,recipients,now);
        }catch(Exception unavailable){fail("OBJECT_EVENT_SOURCE_UNAVAILABLE");return null;}
    }
    public void complete(Capture capture,String outcome){
        if(capture==null)return;try{thread();if(!failure.isEmpty())return;if(queue.size()>=256){fail("OBJECT_EVENT_CAPTURE_BACKPRESSURE");return;}
            var change=new ObjectInteractionFilter.Change(capture.target(),capture.owner(),capture.activation(),capture.objectPosition(),capture.actorKind(),capture.originKnown(),capture.origin(),outcome,capture.subscriptions());
            queue.addLast(new Pending(new RuntimeEventStore.Event(capture.id(),MineAgentRuntimeServices.worldId(server),sourceEpoch,ObjectInteractionFilter.SOURCE,UUID.fromString(capture.actor().ref().id()),capture.actor(),capture.occurredAt(),capture.origin().taskId(),capture.origin().subscriptionChain(),null,null,null,change)));
        }catch(Exception invalid){fail("OBJECT_EVENT_SOURCE_UNAVAILABLE");}
    }
    private void fail(String code){if(failure.isEmpty()){failure=code;failureEpoch.incrementAndGet();leases.values().forEach(v->v.valid().set(false));}}
    /** Runs outside the Native package callback and its SQL lifecycles. */
    public void tick()throws Exception{
        thread();reconcileLeases();
        if(!failure.isEmpty()){
            for(var sub:store.subscriptionsForRuntime())if(sub.state().equals("ACTIVE")&&sub.request().object()!=null)store.pauseObjectSource(sub.id(),sub.revision(),failure);
            queue.clear();failure="";return;
        }
        for(int i=0;i<8&&!queue.isEmpty();i++){
            var pending=queue.getFirst();try{store.ingest(pending.event);queue.removeFirst();committed++;}
            catch(Exception unavailable){if(++pending.failures>=3)fail("OBJECT_EVENT_COMMIT_FAILED");break;}
        }
    }
    public void reconcileDefinitions(Predicate<RuntimeEventStore.Subscription> baseAuthority)throws Exception{
        thread();if(!failure.isEmpty())return;for(var sub:store.subscriptionsForRuntime())if(sub.state().equals("ACTIVE")&&sub.request().object()!=null&&(System.currentTimeMillis()>=sub.expiresAt()||authority.apply(sub)==null&&(!baseAuthority.test(sub)||!store.waitingForSourceForRuntime(sub))))store.pauseObjectSource(sub.id(),sub.revision(),"OBJECT_EVENT_RESOURCE_CHANGED");
    }
    public BooleanSupplier permit(UUID owner,ObjectInteractionFilter.Target target){
        thread();String token=token(owner,target);if(token==null)return ()->false;var descriptor=WorldContentRuntime.get(server).objectEventTarget(owner,target);var key=new LeaseKey(owner,target);var old=leases.get(key);
        if(old!=null&&old.valid().get()&&old.token().equals(token)&&old.object()==descriptor.object())return old.permit();
        if(old!=null)old.valid().set(false);if(old==null&&leases.size()>=512)throw new IllegalStateException("OBJECT_EVENT_LEASE_BUDGET");
        var valid=new AtomicBoolean(true);long epoch=failureEpoch.get();var permissions=MineAgentRuntimeServices.permissions(server);long run=permissions.actionRevision(owner,PermissionAction.RUN_CODE),manage=permissions.actionRevision(owner,PermissionAction.MANAGE_PACKAGES);var library=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).worldLibrary();
        BooleanSupplier permit=()->!closed&&valid.get()&&failureEpoch.get()==epoch&&permissions.actionRevision(owner,PermissionAction.RUN_CODE)==run&&permissions.actionRevision(owner,PermissionAction.MANAGE_PACKAGES)==manage&&library.get(target.packageId()).filter(p->p.enabled()&&p.revision()==target.packageRevision()&&p.canonicalSha256().equals(target.canonicalSha256())).isPresent();
        leases.put(key,new ResourceLease(token,descriptor.object(),valid,permit));return permit;
    }
    private void reconcileLeases(){
        var iterator=leases.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();var value=entry.getValue();
            try{if(!value.valid().get()||!value.token().equals(token(entry.getKey().owner(),entry.getKey().target()))||WorldContentRuntime.get(server).objectEventTarget(entry.getKey().owner(),entry.getKey().target()).object()!=value.object())throw new IllegalStateException();}
            catch(RuntimeException invalid){value.valid().set(false);iterator.remove();}
        }
    }
    public void invalidateInstance(UUID instance){var epoch=instanceEpochs.get(instance);if(epoch!=null)epoch.incrementAndGet();leases.forEach((key,value)->{if(key.target().instanceId().equals(instance))value.valid().set(false);});}
    public Map<String,Object> state(RuntimeEventStore.Subscription sub){String status=!sub.state().equals("ACTIVE")?sub.state():System.currentTimeMillis()>=sub.expiresAt()?"EXPIRED":!failure.isEmpty()?failure:token(sub.creator().scope().ownerId(),sub.request().object().target())==null?(WorldContentRuntime.get(server).objectEventWaiting(sub.creator().scope().ownerId(),sub.request().object().target())?"WAITING_RESOURCE_RESTORE_OR_LOAD":"RESOURCE_UNAVAILABLE"):"MONITORING_NATIVE_CALLBACK";
        return Map.of("phase",status,"pendingCapturedEvents",queue.stream().filter(p->p.event.object().subscriptions().containsKey(sub.id())).count(),"maximumPendingCaptures",256,"maximumCapturesPerTick",64,"maximumCommitsPerTick",8,"businessVerified",false);
    }
    public boolean matches(RuntimeEventStore.Subscription sub,RuntimeEventStore.Event event){
        if(!ObjectInteractionFilter.matches(sub,event))return false;String current=authority.apply(sub);return current!=null&&hash(current).equals(event.object().subscriptions().get(sub.id()).authorityHash());
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("OBJECT_EVENT_HASH",impossible);}}
    public Map<String,Object> forModel(RuntimeEventStore.Event event){var change=Objects.requireNonNull(event.object());
        return Map.of("eventId",event.id(),"source",event.source(),"target",change.target().wire(),"actor",event.author(),"actorKind",change.actorKind(),"occurredAt",event.occurredAt(),"objectPosition",change.objectPosition(),"originKnown",change.originKnown(),"handlerOutcome",change.handlerOutcome(),"businessVerified",false);
    }
    @Override public void close(){closed=true;queue.clear();leases.values().forEach(v->v.valid().set(false));leases.clear();instanceEpochs.clear();}
}
