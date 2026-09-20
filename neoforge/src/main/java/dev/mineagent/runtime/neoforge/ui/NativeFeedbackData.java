package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.delivery.ContentDeliveryStore;
import dev.mineagent.runtime.core.feedback.*;
import dev.mineagent.runtime.core.shared.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Bounded data-only bridge. The FEEDBACK actor is the actual author, not PLAYER or PACKAGE authority. */
final class NativeFeedbackData implements AutoCloseable {
    private final MinecraftServer server;private final SharedStateStore store;private final Predicate<UiFeedbackStore.Scope> standing;private final ObjectMapper json=new ObjectMapper();
    private Access active;private boolean closed;
    private record Access(SharedStateStore.Context context,UiFeedbackStore.Scope source,boolean write,BooleanSupplier permit){}
    NativeFeedbackData(MinecraftServer server,Predicate<UiFeedbackStore.Scope> standing)throws Exception{
        this.server=server;this.standing=standing;store=SharedStateStore.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server),this::authorized);
    }
    private void thread(){if(closed||!server.isSameThread())throw new IllegalStateException("FEEDBACK_DATA_THREAD");}
    private WorldContentRuntime.SharedDescriptor resource(UUID owner,UUID agent,UUID pack,long revision,String canonical,FeedbackDataBinding binding)throws Exception{
        thread();if(binding==null||!MineAgentRuntimeServices.permissions(server).trustedActions(owner).contains(PermissionAction.ACCESS_SHARED_STATE))throw new SecurityException("FEEDBACK_DATA_GRANT_REQUIRED");
        var definition=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElseThrow();
        if(!MineAgentRuntimeServices.permissions(server).canMutateAgent(definition,owner,false))throw new SecurityException("FEEDBACK_DATA_AGENT_AUTHORITY");
        var descriptor=WorldContentRuntime.get(server).sharedDescriptor(binding.instanceId());
        if(!descriptor.owner().equals(owner)||!descriptor.packageId().equals(pack)||descriptor.packageRevision()!=revision||!descriptor.canonicalSha256().equals(canonical))throw new SecurityException("FEEDBACK_DATA_INSTANCE_CHANGED");
        var context=new SharedStateStore.Context(new SharedStateStore.Scope(MineAgentRuntimeServices.worldId(server),pack,binding.instanceId(),binding.namespace()),owner,owner,revision,canonical,"FEEDBACK");
        var schema=store.schemaForRuntime(context.scope());if(schema.schemaVersion()!=binding.schemaVersion()||!schema.owner().equals(owner))throw new SecurityException("FEEDBACK_DATA_SCHEMA_CHANGED");
        for(String key:binding.readKeys()){var field=schema.fields().get(key);if(field==null||!field.canRead(context))throw new SecurityException("FEEDBACK_DATA_READ_KEY");}
        for(String key:binding.writeKeys()){var field=schema.fields().get(key);if(field==null||!field.canWrite(context))throw new SecurityException("FEEDBACK_DATA_WRITE_KEY");}
        return descriptor;
    }
    String token(UUID owner,UUID agent,ContentDeliveryStore.Asset asset){try{
        var r=resource(owner,agent,asset.packageId(),asset.revision(),asset.canonical(),asset.feedbackData());var p=MineAgentRuntimeServices.permissions(server);
        return r.activationId()+"|"+asset.feedbackData()+"|"+p.actionRevision(owner,PermissionAction.ACCESS_SHARED_STATE)+"|"+p.actionRevision(owner,PermissionAction.RUN_CODE)+"|"+p.actionRevision(owner,PermissionAction.MANAGE_PACKAGES);
    }catch(Exception denied){return null;}}
    private boolean authorized(SharedStateStore.Context c,boolean write,boolean admin){
        var a=active;if(a==null||a.context()!=c||admin||write&&!a.write()||closed||!server.isSameThread())return false;
        try{var s=a.source();resource(s.ownerId(),s.agentId(),s.packageId(),s.packageRevision(),s.canonicalSha256(),s.dataBinding());return a.permit().getAsBoolean()&&standing.test(s);}catch(Exception denied){return false;}
    }
    @FunctionalInterface private interface Work<T>{T run(SharedStateStore.Context context)throws Exception;}
    private <T>T access(UiFeedbackStore.Scope s,boolean write,BooleanSupplier permit,Work<T> action)throws Exception{
        thread();if(active!=null)throw new IllegalStateException("FEEDBACK_DATA_REENTRANT_ACCESS");var b=Objects.requireNonNull(s.dataBinding());resource(s.ownerId(),s.agentId(),s.packageId(),s.packageRevision(),s.canonicalSha256(),b);
        var c=new SharedStateStore.Context(new SharedStateStore.Scope(s.worldId(),s.packageId(),b.instanceId(),b.namespace()),s.ownerId(),s.authorId(),s.packageRevision(),s.canonicalSha256(),"FEEDBACK");
        active=new Access(c,s,write,permit);try{return action.run(c);}finally{active=null;}
    }
    String state(UiFeedbackStore.Scope scope)throws Exception{return access(scope,false,()->true,c->{String value=json.writeValueAsString(store.read(c,scope.dataBinding().readKeys()));if(value.getBytes(StandardCharsets.UTF_8).length>16000)throw new IllegalStateException("FEEDBACK_STATE_OUTPUT_BUDGET");return value;});}
    FeedbackTransactionPlan plan(UiFeedbackStore.Item item,BooleanSupplier permit)throws Exception{
        String snapshot=access(item.scope(),false,permit,c->json.writeValueAsString(store.read(c,item.scope().dataBinding().readKeys())));
        if(snapshot.getBytes(StandardCharsets.UTF_8).length>16000)throw new IllegalStateException("FEEDBACK_SNAPSHOT_BUDGET");
        return FeedbackTransactionPlan.parse(item.scope().dataBinding(),WorldContentRuntime.get(server).planFeedback(item.scope(),item.id(),item.payload(),snapshot));
    }
    SharedStateStore.Receipt apply(UiFeedbackStore.Item item,FeedbackTransactionPlan plan,BooleanSupplier permit)throws Exception{
        item.scope().dataBinding().validate(plan.transaction());return access(item.scope(),true,permit,c->store.transact(c,item.id(),plan.canonical(),new SharedStateStore.Provenance(item.scope().originTaskId(),item.scope().originIntentRevision(),item.scope().causalChain())));
    }
    FeedbackTransactionOutcome recovery(UiFeedbackStore.Item item){return FeedbackTransactionOutcome.inspect(item,()->access(item.scope(),false,()->true,c->store.transactionReceipt(c,item.id(),item.dataPlan().canonical(),new SharedStateStore.Provenance(item.scope().originTaskId(),item.scope().originIntentRevision(),item.scope().causalChain()))));}
    boolean verify(UiFeedbackStore.Item item,UiFeedbackStore.Completion proof){try{
        if(!item.scope().policy().mode().equals("DETERMINISTIC")||item.dataPlan()==null||!item.id().equals(proof.operationId())||!Set.of("SHARED_TRANSACTION_APPLIED","SHARED_TRANSACTION_CONFLICT").contains(proof.code()))return false;
        var receipt=access(item.scope(),false,()->true,c->store.transactionReceipt(c,item.id(),item.dataPlan().canonical(),new SharedStateStore.Provenance(item.scope().originTaskId(),item.scope().originIntentRevision(),item.scope().causalChain())).orElseThrow());
        return matchesReceipt(proof,receipt,item.dataPlan().sha256());
    }catch(Exception denied){return false;}}
    static boolean matchesReceipt(UiFeedbackStore.Completion proof,SharedStateStore.Receipt receipt,String planHash){try{
        var mapper=new ObjectMapper();var value=mapper.readTree(proof.result());
        // Compare both sides in persisted JSON form: in-memory long fields are
        // LongNode, while the same small JSON integer reads back as IntNode.
        // Do not coerce string/float revisions or ignore unknown receipt fields.
        return value.isObject()&&value.size()==2&&value.path("planSha256").isTextual()
                &&proof.code().equals("SHARED_TRANSACTION_"+receipt.status())
                &&value.path("receipt").equals(mapper.readTree(mapper.writeValueAsBytes(receipt)))&&value.path("planSha256").asText().equals(planHash);
    }catch(Exception invalid){return false;}}
    @Override public void close()throws Exception{closed=true;active=null;store.close();}
}
