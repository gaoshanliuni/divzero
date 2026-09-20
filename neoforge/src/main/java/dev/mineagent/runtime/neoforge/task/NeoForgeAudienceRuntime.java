package dev.mineagent.runtime.neoforge.task;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.directory.ObjectRef;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.core.directory.*;
import dev.mineagent.runtime.core.task.WorldGoalCheck;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import java.time.Clock;
import java.util.*;

/** Native read-only addressing boundary. Persistent audiences never stand in for delivery grants or client acknowledgements. */
public final class NeoForgeAudienceRuntime implements AutoCloseable {
    private final NeoForgeObjectDirectory directory;private final AudienceStore store;private final ObjectMapper json=new ObjectMapper();
    private long selections;
    public NeoForgeAudienceRuntime(MinecraftServer server,NeoForgeObjectDirectory directory)throws Exception{
        this.directory=directory;store=AudienceStore.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server),Clock.systemUTC(),new AudienceStore.Port(){
            public void authorize(AudienceStore.Context c){if(!server.isSameThread()||!c.serverEpoch().equals(directory.serverEpoch()))throw new SecurityException("AUDIENCE_SERVER_CHANGED");directory.core().authorize(c.scope());}
            public List<ObjectRef> select(AudienceStore.Context c,ObjectQuery query){selections++;return directory.core().selectCurrent(c.scope(),query,64).stream().map(ObjectDirectory.Entry::ref).toList();}
            public AudienceStore.Recipient assess(AudienceStore.Context c,ObjectQuery query,UUID id){
                var entry=directory.core().lookup(c.scope(),ObjectRef.Kind.PLAYER,id.toString()).orElse(null);if(entry==null||!entry.online())return new AudienceStore.Recipient(id,"UNAVAILABLE",null);
                var checked=directory.core().revalidate(c.scope(),entry.ref(),query);
                return switch(checked.status()){case "CURRENT"->new AudienceStore.Recipient(id,"ELIGIBLE",entry.ref());case "FILTER_CHANGED"->new AudienceStore.Recipient(id,"FILTER_CHANGED",null);case "OBJECT_CHANGED","STALE_GENERATION"->new AudienceStore.Recipient(id,"GENERATION_CHANGED",null);default->new AudienceStore.Recipient(id,"UNAVAILABLE",null);};
            }
        });
    }
    public AudienceStore store(){return store;}
    public long selectionCalls(){return selections;}
    public AudienceStore.Context context(ManagedTask task,UUID operation){return new AudienceStore.Context(directory.currentScope(task),directory.serverEpoch(),operation);}
    public Map<String,String> execute(ManagedTask task,UUID operation,String tool,String source)throws Exception{
        var request=AudienceToolRequest.parse(tool,source);var c=context(task,operation);Object result;
        switch(tool){
            case "create_audience"->result=store.create(c,request.create());
            case "resolve_audience"->result=store.resolve(c,request.id(),request.expectedRevision(),operation); // Immediate invocation, not a model-invented event id.
            case "revoke_audience"->result=store.revoke(c,request.id(),request.expectedRevision());
            case "inspect_audience"->result=switch(request.inspectKind()){
                case "DEFINITION"->store.inspect(c,request.id());case "SNAPSHOT"->store.inspectSnapshot(c,request.id());
                case "OPERATION"->{var op=store.operation(c,request.id());yield Map.of("operationId",op.operationId(),"kind",op.kind(),"state",op.state(),"subjectId",op.subjectId(),"error",op.error(),"createdAt",op.createdAt());}
                default->throw new IllegalArgumentException("AUDIENCE_INSPECTION_KIND");};
            default->throw new IllegalArgumentException("AUDIENCE_TOOL");
        }
        return Map.of("executionMode","NATIVE_AUDIENCE_ADDRESSING_ONLY","result",json.writeValueAsString(result),"request",request.canonical(),"serverEpoch",directory.serverEpoch().toString(),"authorityRevision",Long.toString(directory.epoch(task.ownerPlayerId())),"operationId",operation.toString(),"deliveryVerified","false");
    }
    private void currentDefinition(AudienceStore.Definition d,boolean allowRevoked){if(System.currentTimeMillis()>=d.expiresAt()||!allowRevoked&&!d.state().equals("ACTIVE"))throw new IllegalStateException("AUDIENCE_INACTIVE");}
    private boolean currentSnapshot(AudienceStore.Context context,AudienceStore.Snapshot snapshot)throws Exception{
        if(!context.serverEpoch().equals(snapshot.context().serverEpoch())||context.scope().authorityRevision()!=snapshot.context().scope().authorityRevision())return false;
        var definition=store.inspect(context,snapshot.audienceId());currentDefinition(definition,false);if(definition.revision()!=snapshot.definitionRevision()||!snapshot.state().equals("RESOLVED"))return false;
        for(var recipient:snapshot.recipients())if(recipient.status().equals("ELIGIBLE")&&!directory.core().revalidate(context.scope(),recipient.ref(),definition.query()).status().equals("CURRENT"))return false;return true;
    }
    public Map<String,String> forModel(ManagedTask task,String tool,Map<String,String> after){
        if(!AudienceToolRequest.TOOLS.contains(tool))return after;
        try{
            if(!directory.serverEpoch().toString().equals(after.get("serverEpoch"))||!Long.toString(directory.epoch(task.ownerPlayerId())).equals(after.get("authorityRevision")))throw new IllegalStateException();
            var c=context(task,UUID.randomUUID());directory.core().authorize(c.scope());var request=AudienceToolRequest.parse(tool,after.get("request"));
            if(tool.equals("resolve_audience")||tool.equals("inspect_audience")&&request.inspectKind().equals("SNAPSHOT")){var value=json.readValue(after.get("result"),AudienceStore.Snapshot.class);if(value.state().equals("RESOLVED")&&!currentSnapshot(c,value))throw new IllegalStateException();store.inspectSnapshot(c,value.snapshotId());}
            else if(!tool.equals("inspect_audience")||request.inspectKind().equals("DEFINITION")){var value=json.readValue(after.get("result"),AudienceStore.Definition.class);var current=store.inspect(c,value.audienceId());currentDefinition(current,true);if(!value.equals(current))throw new IllegalStateException();}
            else store.operation(c,request.id());
            return after;
        }catch(Exception stale){return Map.of("executionMode","AUDIENCE_OBSERVATION_REDACTED","reason","CURRENT_AUTHORITY_DEFINITION_AND_REFERENCE_REQUIRED");}
    }
    public Map<String,Object> verify(ManagedTask task,WorldGoalCheck check)throws Exception{
        var c=context(task,UUID.randomUUID());
        if(check.kind().equals("audience_definition")){var d=store.inspect(c,UUID.fromString(check.id()));currentDefinition(d,true);return Map.of("scope","AUDIENCE_CONFIGURATION_ONLY","matched",d.revision()==Long.parseLong(check.details().get("revision"))&&d.state().equals(check.details().get("state")),"definition",d);}
        var s=store.inspectSnapshot(c,UUID.fromString(check.id()));long eligible=s.recipients().stream().filter(r->r.status().equals("ELIGIBLE")).count();return Map.of("scope","RECIPIENT_RESOLUTION_ONLY","matched",eligible>=check.count()&&currentSnapshot(c,s),"snapshot",s,"eligible",eligible,"deliveryVerified",false);
    }
    @Override public void close()throws Exception{store.close();}
}
