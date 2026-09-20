package dev.mineagent.runtime.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.directory.ObjectQuery;
import dev.mineagent.runtime.core.shared.SharedStateStore;
import java.util.*;

/** A pinned managed object and explicit actor selection, not a resource or identity grant. */
public record ObjectInteractionFilter(Target target,Set<String> actorKinds,List<UUID> actorIds){
    public static final String SOURCE="MANAGED_OBJECT_INTERACT";
    public static final Set<String> FIELDS=Set.of("package_id","instance_id","package_revision","canonical_sha256","entity_id","part","asset_sha256","actor_kinds","actor_ids");
    public record Target(UUID packageId,UUID instanceId,long packageRevision,String canonicalSha256,UUID entityId,String part,String assetSha256){
        public Target{Objects.requireNonNull(packageId);Objects.requireNonNull(instanceId);Objects.requireNonNull(entityId);
            if(packageRevision<1||!hash(canonicalSha256)||!hash(assetSha256)||part==null||!part.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("OBJECT_EVENT_TARGET");}
        public Map<String,Object> wire(){var data=new LinkedHashMap<String,Object>();data.put("package_id",packageId);data.put("instance_id",instanceId);data.put("package_revision",packageRevision);data.put("canonical_sha256",canonicalSha256);data.put("entity_id",entityId);data.put("part",part);data.put("asset_sha256",assetSha256);return data;}
    }
    public ObjectInteractionFilter{Objects.requireNonNull(target);actorKinds=Collections.unmodifiableSet(new TreeSet<>(actorKinds));actorIds=actorIds.stream().sorted().toList();
        if(actorKinds.isEmpty()||!Set.of("PLAYER","AGENT").containsAll(actorKinds)||actorIds.size()>64||new HashSet<>(actorIds).size()!=actorIds.size())throw new IllegalArgumentException("OBJECT_EVENT_ACTORS");}
    public static ObjectInteractionFilter parse(JsonNode n){
        var revision=n.get("package_revision");if(revision==null||!revision.isIntegralNumber()||!revision.canConvertToLong())throw new IllegalArgumentException("OBJECT_EVENT_REVISION");
        var target=new Target(UUID.fromString(text(n,"package_id")),UUID.fromString(text(n,"instance_id")),revision.longValue(),text(n,"canonical_sha256"),UUID.fromString(text(n,"entity_id")),text(n,"part"),text(n,"asset_sha256"));
        var kinds=new TreeSet<String>();if(!n.path("actor_kinds").isArray()||n.get("actor_kinds").size()>2)throw new IllegalArgumentException("OBJECT_EVENT_ACTORS");
        for(var kind:n.get("actor_kinds"))if(!kind.isTextual()||!kinds.add(kind.textValue()))throw new IllegalArgumentException("OBJECT_EVENT_ACTORS");
        var ids=new ArrayList<UUID>();if(n.has("actor_ids")){if(!n.get("actor_ids").isArray()||n.get("actor_ids").size()>64)throw new IllegalArgumentException("OBJECT_EVENT_ACTORS");for(var id:n.get("actor_ids")){if(!id.isTextual())throw new IllegalArgumentException("OBJECT_EVENT_ACTORS");ids.add(UUID.fromString(id.textValue()));}}
        return new ObjectInteractionFilter(target,kinds,ids);
    }
    public boolean selects(UUID actor,String kind){return actorKinds.contains(kind)&&(actorIds.isEmpty()||actorIds.contains(actor));}
    public Map<String,Object> wire(){var data=new LinkedHashMap<>(target.wire());data.put("actor_kinds",actorKinds);data.put("actor_ids",actorIds);return data;}
    public record Recipient(long revision,String authorityHash){public Recipient{if(revision<1||!hash(authorityHash))throw new IllegalArgumentException("OBJECT_EVENT_RECIPIENT");}}
    public record Change(Target target,UUID owner,UUID activationId,ObjectQuery.Point objectPosition,String actorKind,boolean originKnown,SharedStateStore.Provenance origin,String handlerOutcome,Map<UUID,Recipient> subscriptions){
        public Change{Objects.requireNonNull(target);Objects.requireNonNull(owner);Objects.requireNonNull(activationId);Objects.requireNonNull(objectPosition);Objects.requireNonNull(origin);
            subscriptions=Collections.unmodifiableMap(new TreeMap<>(subscriptions));
            if(!Set.of("PLAYER","AGENT").contains(actorKind)||!Set.of("RETURNED_TRUE","RETURNED_FALSE","THREW").contains(handlerOutcome)||subscriptions.isEmpty()||subscriptions.size()>128||subscriptions.values().stream().anyMatch(Objects::isNull)
                    ||actorKind.equals("PLAYER")&&(!originKnown||!origin.equals(SharedStateStore.Provenance.NONE))||actorKind.equals("AGENT")&&(originKnown?origin.taskId()==null:!origin.equals(SharedStateStore.Provenance.NONE)))throw new IllegalArgumentException("OBJECT_EVENT_CHANGE");}
    }
    public static void validate(Change change,dev.mineagent.runtime.core.directory.ObjectDirectory.Entry actor){
        if(actor==null||!actor.online()||actor.position()==null||!actor.dimension().equals(change.objectPosition().dimension()))throw new IllegalArgumentException("OBJECT_EVENT_POSITION");
        double dx=actor.position().x()-change.objectPosition().x(),dy=actor.position().y()-change.objectPosition().y(),dz=actor.position().z()-change.objectPosition().z();
        if(dx*dx+dy*dy+dz*dz>64)throw new IllegalArgumentException("OBJECT_EVENT_OUT_OF_REACH");
    }
    public static boolean matches(RuntimeEventStore.Subscription sub,RuntimeEventStore.Event event){var filter=sub.request().object();var change=event.object();
        return filter!=null&&change!=null&&change.subscriptions().containsKey(sub.id())&&change.subscriptions().get(sub.id()).revision()==sub.revision()&&filter.target().equals(change.target())&&sub.creator().scope().ownerId().equals(change.owner())
                &&filter.selects(event.author(),change.actorKind())&&(sub.request().mode().equals("RECORD_ONLY")||change.originKnown());}
    private static String text(JsonNode n,String key){if(!n.path(key).isTextual())throw new IllegalArgumentException("OBJECT_EVENT_TEXT");return n.get(key).textValue();}
    private static boolean hash(String text){return text!=null&&text.matches("[a-f0-9]{64}");}
}
