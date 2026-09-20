package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.directory.ObjectRef;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.directory.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.content.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-thread discovery. Explicit read grant is required even for OP; it never grants actor OP or display/write authority. */
public final class NeoForgeObjectDirectory implements AutoCloseable {
    private final MinecraftServer server;private final UUID serverEpoch=UUID.randomUUID();private final ObjectMapper json=new ObjectMapper();
    private final ObjectDirectory directory;private volatile boolean closed;
    private final ObjectRevisionLedger revisions=new ObjectRevisionLedger(16384);
    private record Incarnation(WeakReference<Object> instance,UUID generation){}
    private final Map<String,Incarnation> identities=new HashMap<>();private Set<UUID> ownedInstances=Set.of();
    public NeoForgeObjectDirectory(MinecraftServer server){this.server=server;directory=new ObjectDirectory(new NativePort(),System::currentTimeMillis);}
    public long epoch(UUID owner){return MineAgentRuntimeServices.permissions(server).actionRevision(owner,PermissionAction.DISCOVER_OBJECTS);}
    public boolean sameEpoch(UUID owner,long expected){return !closed&&epoch(owner)==expected;}
    public boolean explicitlyGranted(UUID owner){return MineAgentRuntimeServices.permissions(server).trustedActions(owner).contains(PermissionAction.DISCOVER_OBJECTS);}
    public UUID serverEpoch(){return serverEpoch;}
    public ObjectDirectory core(){return directory;}
    public ObjectDirectory.Scope currentScope(ManagedTask task){return scope(task);}
    public ObjectDirectory.Entry observeNativePlayer(ServerPlayer player){if(!server.isSameThread()||player.level().getServer()!=server||player instanceof MineAgentPlayer)throw new IllegalArgumentException("EVENT_PLAYER_SOURCE");return player(player);}
    /** Internal Native interaction capture only. AGENT remains explicitly labelled by the event, never presented as a human. */
    public ObjectDirectory.Entry observeNativeInteractionActor(ServerPlayer actor){if(!server.isSameThread()||actor.level().getServer()!=server)throw new IllegalArgumentException("OBJECT_EVENT_ACTOR_SOURCE");return player(actor);}
    public ObjectDirectory.Entry observeNativeManagedObject(RuntimeObjectEntity object){if(!server.isSameThread()||object.level().getServer()!=server||!WorldContentRuntime.get(server).objectActive(object))throw new IllegalArgumentException("OBJECT_EVENT_TARGET_SOURCE");return entity(object);}
    private ObjectDirectory.Scope scope(ManagedTask task){return new ObjectDirectory.Scope(task.worldId(),task.agentId(),task.ownerPlayerId(),task.taskId(),task.intentRevision(),epoch(task.ownerPlayerId()));}
    public Map<String,String> query(ManagedTask task,UUID operation,String arguments)throws Exception{
        var query=ObjectQuery.parse(arguments);var result=directory.query(scope(task),operation,query);
        return evidence(task,query,json.writeValueAsString(result),"NATIVE_OBJECT_DIRECTORY_READ");
    }
    public Map<String,String> revalidate(ManagedTask task,UUID operation,String arguments)throws Exception{
        var request=ObjectRevalidation.parse(arguments);var scope=scope(task);var values=request.refs().stream().map(ref->directory.revalidate(scope,ref,request.query())).toList();
        return evidence(task,request.query(),json.writeValueAsString(Map.of("operationId",operation,"scope",scope,"results",values,"discoveryOnly",true)),"NATIVE_OBJECT_REFERENCE_VALIDATION");
    }
    private Map<String,String> evidence(ManagedTask task,ObjectQuery query,String result,String mode){return Map.of("executionMode",mode,"result",result,"query",query.canonical(),"serverEpoch",serverEpoch.toString(),"authorityRevision",Long.toString(epoch(task.ownerPlayerId())));}
    /** Do not forward historical directory rows into another model request after revoke/regrant, restart or target invalidation. */
    public Map<String,String> forModel(ManagedTask task,String tool,Map<String,String> after){
        if(!Set.of("query_objects","revalidate_objects").contains(tool))return after;
        try{
            if(!serverEpoch.toString().equals(after.get("serverEpoch"))||!Long.toString(epoch(task.ownerPlayerId())).equals(after.get("authorityRevision")))throw new IllegalStateException();
            var scope=scope(task);new NativePort().authorize(scope);var query=ObjectQuery.parse(after.get("query"));var result=json.readTree(after.get("result"));
            var values=tool.equals("query_objects")?result.path("items"):result.path("results");if(!values.isArray()||values.size()>32)throw new IllegalStateException();
            for(var value:values){var node=tool.equals("query_objects")?value.path("ref"):value.path("requested");var ref=json.treeToValue(node,ObjectRef.class);if(!directory.revalidate(scope,ref,query).status().equals("CURRENT"))throw new IllegalStateException();}
            return after;
        }catch(Exception invalid){return Map.of("executionMode","DIRECTORY_OBSERVATION_REDACTED","reason","CURRENT_AUTHORITY_OR_REFERENCE_REQUIRED");}
    }
    private final class NativePort implements ObjectDirectory.Port {
        public void authorize(ObjectDirectory.Scope scope){
            if(closed||!server.isSameThread()||!scope.worldId().equals(MineAgentRuntimeServices.worldId(server))||!explicitlyGranted(scope.ownerId())||scope.authorityRevision()!=epoch(scope.ownerId()))throw new SecurityException("DIRECTORY_PERMISSION_REQUIRED");
            var task=MineAgentRuntimeServices.tasks(server).get(scope.taskId()).orElse(null);
            if(task==null||task.status()!=TaskStatus.RUNNING||task.intentRevision()!=scope.intentRevision()||!task.agentId().equals(scope.agentId())||!task.ownerPlayerId().equals(scope.ownerId())||!MineAgentRuntimeServices.taskExecutor(server).authorized(task))throw new SecurityException("DIRECTORY_TASK_REVOKED");
            ownedInstances=WorldContentRuntime.get(server).list(scope.ownerId()).stream().map(a->a.instanceId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if(ownedInstances.size()>4096)throw new IllegalStateException("DIRECTORY_INSTANCE_BUDGET");
        }
        public Iterable<ObjectDirectory.Entry> scan(ObjectDirectory.Scope scope,ObjectRef.Kind kind){
            var result=new ArrayList<ObjectDirectory.Entry>();int scanned=0;
            switch(kind){
                case PLAYER->{for(var player:server.getPlayerList().getPlayers()){if(++scanned>4096)throw new IllegalStateException("DIRECTORY_SCAN_BUDGET");if(!(player instanceof MineAgentPlayer))result.add(player(player));}}
                case TEAM->{for(var team:server.getScoreboard().getPlayerTeams()){scanned+=1+team.getPlayers().size();if(scanned>4096)throw new IllegalStateException("DIRECTORY_TEAM_BUDGET");result.add(team(team));}}
                case DIMENSION->{for(var level:server.getAllLevels()){if(++scanned>64)throw new IllegalStateException("DIRECTORY_DIMENSION_BUDGET");String id=level.dimension().identifier().toString();result.add(entry(kind,id,level,id,id,null,"",true,id));}}
                case ENTITY->{for(var level:server.getAllLevels())for(var entity:level.getAllEntities()){if(++scanned>4096)throw new IllegalStateException("DIRECTORY_SCAN_BUDGET");if(!(entity instanceof net.minecraft.world.entity.player.Player)&&visibleEntity(entity))result.add(entity(entity));}}
                case INSTANCE->{var world=WorldContentRuntime.get(server);for(var activation:world.list(scope.ownerId())){if(++scanned>4096)throw new IllegalStateException("DIRECTORY_SCAN_BUDGET");instance(scope,activation.instanceId()).ifPresent(result::add);}}
            }return result;
        }
        public Optional<ObjectDirectory.Entry> current(ObjectDirectory.Scope scope,ObjectRef ref){
            if(!ref.worldId().equals(scope.worldId()))return Optional.empty();
            return switch(ref.kind()){
                case PLAYER->{var p=server.getPlayerList().getPlayer(UUID.fromString(ref.id()));yield p==null||p instanceof MineAgentPlayer?Optional.empty():Optional.of(player(p));}
                case TEAM->{var t=server.getScoreboard().getPlayerTeam(ref.id());yield t==null?Optional.empty():Optional.of(team(t));}
                case DIMENSION->{ObjectDirectory.Entry value=null;for(var level:server.getAllLevels())if(level.dimension().identifier().toString().equals(ref.id()))value=entry(ref.kind(),ref.id(),level,ref.id(),ref.id(),null,"",true,ref.id());yield Optional.ofNullable(value);}
                case ENTITY->{Entity entity=findEntity(ref.id());yield entity==null||entity instanceof net.minecraft.world.entity.player.Player||!visibleEntity(entity)?Optional.empty():Optional.of(entity(entity));}
                case INSTANCE->instance(scope,UUID.fromString(ref.id()));
            };
        }
        public boolean visible(ObjectDirectory.Scope scope,ObjectDirectory.Entry entry){return entry.ref().worldId().equals(scope.worldId())&&(entry.ref().kind()!=ObjectRef.Kind.INSTANCE||ownedInstances.contains(UUID.fromString(entry.ref().id())));}
        public ObjectQuery.Point actor(ObjectDirectory.Scope scope){var body=MineAgentRuntimeServices.bodies(server).body(scope.agentId()).orElse(null);return body==null||!body.isAlive()?null:point(body);}
    }
    private Entity findEntity(String id){UUID uuid=UUID.fromString(id);int levels=0;for(var level:server.getAllLevels()){if(++levels>64)throw new IllegalStateException("DIRECTORY_DIMENSION_BUDGET");var entity=level.getEntity(uuid);if(entity!=null&&!entity.isRemoved())return entity;}return null;}
    private boolean visibleEntity(Entity entity){return !entity.isRemoved()&&(!(entity instanceof RuntimeObjectEntity object)||object.header()!=null&&ownedInstances.contains(object.header().instance()));}
    private ObjectDirectory.Entry player(ServerPlayer player){String team=player.getTeam()==null?"":player.getTeam().getName(),dimension=player.level().dimension().identifier().toString();String name=player.getGameProfile().name();return entry(ObjectRef.Kind.PLAYER,player.getUUID().toString(),player,name,dimension,point(player),team,true,name+"|"+dimension+"|"+team);}
    private ObjectDirectory.Entry entity(Entity entity){String type=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),dimension=entity.level().dimension().identifier().toString(),name=bounded(entity.getName().getString());return entry(ObjectRef.Kind.ENTITY,entity.getUUID().toString(),entity,name,dimension,point(entity),"",true,type+"|"+dimension+"|"+name);}
    private ObjectDirectory.Entry team(PlayerTeam team){if(team.getPlayers().size()>4096)throw new IllegalStateException("DIRECTORY_TEAM_BUDGET");String name=bounded(team.getDisplayName().getString());return entry(ObjectRef.Kind.TEAM,team.getName(),team,name,"",null,"",true,name+"|"+String.join("\n",team.getPlayers().stream().sorted().toList()));}
    private Optional<ObjectDirectory.Entry> instance(ObjectDirectory.Scope scope,UUID id){
        if(!ownedInstances.contains(id))return Optional.empty();var world=WorldContentRuntime.get(server);var value=world.instance(id).orElse(null);if(value==null)return Optional.empty();
        var activation=world.list(scope.ownerId()).stream().filter(a->a.instanceId().equals(id)).findFirst().orElse(null);if(activation==null)return Optional.empty();
        var l=value.location();var p=new ObjectQuery.Point(l.dimension(),l.x(),l.y(),l.z());boolean active=world.active(id);
        var generation=UUID.nameUUIDFromBytes((serverEpoch+"|"+activation.operationId()+"|"+activation.canonicalSha256()+"|"+value.definitionRevision()).getBytes(StandardCharsets.UTF_8));
        var ref=new ObjectRef(scope.worldId(),ObjectRef.Kind.INSTANCE,id.toString(),generation,revisions.observe("INSTANCE:"+id,generation,signature(value.definitionId()+"|"+value.definitionRevision()+"|"+l+"|"+active)));
        var pack=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).worldLibrary().get(value.packageId()).orElse(null);var definition=pack==null?null:pack.definitions().get(value.definitionId());
        return Optional.of(new ObjectDirectory.Entry(ref,definition==null?value.definitionId().toString():bounded(definition.name()),l.dimension(),p,"",active));
    }
    private ObjectDirectory.Entry entry(ObjectRef.Kind kind,String id,Object instance,String name,String dimension,ObjectQuery.Point position,String team,boolean online,String signature){
        String key=kind+":"+id;var old=identities.get(key);UUID generation;
        if(old!=null&&old.instance().get()==instance)generation=old.generation();else{
            identities.entrySet().removeIf(e->{boolean gone=e.getValue().instance().get()==null||e.getValue().instance().get() instanceof Entity entity&&entity.isRemoved();if(gone)revisions.forget(e.getKey());return gone;});
            if(!identities.containsKey(key)&&identities.size()>=16384)throw new IllegalStateException("DIRECTORY_IDENTITY_BUDGET");generation=UUID.randomUUID();identities.put(key,new Incarnation(new WeakReference<>(instance),generation));
        }
        return new ObjectDirectory.Entry(new ObjectRef(MineAgentRuntimeServices.worldId(server),kind,id,generation,revisions.observe(key,generation,signature(signature))),bounded(name),dimension,position,team,online);
    }
    private static ObjectQuery.Point point(Entity entity){return new ObjectQuery.Point(entity.level().dimension().identifier().toString(),entity.getX(),entity.getY(),entity.getZ());}
    private static String bounded(String value){return value.length()<=256?value:value.substring(0,value.offsetByCodePoints(0,Math.min(128,value.codePointCount(0,value.length()))));}
    private static String signature(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public void close(){closed=true;directory.clear();identities.clear();revisions.clear();ownedInstances=Set.of();}
}
