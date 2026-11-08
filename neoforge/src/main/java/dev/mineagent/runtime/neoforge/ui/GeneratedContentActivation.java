package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.PackageGenerationJob;
import dev.mineagent.runtime.core.persistence.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.task.ServerAuthority;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Publication receipts and activation receipts are separate. A submitted write is never replayed on recovery. */
final class GeneratedContentActivation implements AutoCloseable {
    private static final String CONTEXT="generated_activation_context",RESULT="generated_activation_result";
    private final ObjectMapper json=new ObjectMapper();
    private final MinecraftServer server;
    private final ServerPackageRuntime packages;
    private final SqliteRuntimeRepository db;
    private final UUID world;
    private static final String CLIENT_QUEUE="generated_resource_pending";
    record ClientResource(UUID operation,UUID owner,UUID packageId,long revision,String canonical,long runGeneration,long manageGeneration){}
    private final Map<UUID,ClientResource> clientQueue=new LinkedHashMap<>();
    record Context(UUID owner,UUID agent,RuntimeInstanceLocation anchor,long runGeneration,long manageGeneration){}
    GeneratedContentActivation(MinecraftServer server,ServerPackageRuntime packages)throws Exception {
        this.server=server;this.packages=packages;world=MineAgentRuntimeServices.worldId(server);
        db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));
        for(var row:db.list(world,CLIENT_QUEUE)){var item=json.readValue(row.payload(),ClientResource.class);clientQueue.put(item.operation(),item);}
    }
    void capture(ServerPlayer player,PackageGenerationJob job)throws Exception {
        if(!job.purpose().equals("WORLD_CONTENT"))return;
        var config=MineAgentRuntimeServices.config(server);
        var context=new Context(player.getUUID(),job.agentId(),new RuntimeInstanceLocation(player.level().dimension().identifier().toString(),player.getX()+2,player.getY(),player.getZ(),0,0),
                config.permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE),config.permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES));
        var saved=db.compareAndSet(world,CONTEXT,job.operationId().toString(),0,json.writeValueAsString(context),System.currentTimeMillis());
        if(!saved.accepted())throw new IllegalStateException("GENERATED_ACTIVATION_CONTEXT_EXISTS");
    }
    private void save(UUID operation,Map<String,Object> result)throws Exception {
        var old=db.get(world,RESULT,operation.toString());
        if(!db.compareAndSet(world,RESULT,operation.toString(),old.map(RuntimeRecord::revision).orElse(0L),json.writeValueAsString(result),System.currentTimeMillis()).accepted())throw new IllegalStateException("GENERATED_ACTIVATION_RECEIPT_CONFLICT");
    }
    private UUID operation(UUID generation,String action)throws Exception {
        return AssignedOperationIds.resolve(db,world,"generated_activation_ids",generation+":"+action,List.of());
    }
    void published(PackageGenerationJob job,RuntimePackage original)throws Exception {
        if(!job.state().equals("PUBLISHED")||!job.purpose().equals("WORLD_CONTENT"))return;
        if(db.get(world,RESULT,job.operationId().toString()).isPresent())return;
        var row=db.get(world,CONTEXT,job.operationId().toString());
        if(row.isEmpty()){save(job.operationId(),Map.of("status","PUBLISHED_LIFECYCLE_REQUIRED","error","GENERATION_CONTEXT_NOT_RECORDED","executed",false));return;}
        var context=json.readValue(row.get().payload(),Context.class);
        var player=server.getPlayerList().getPlayer(job.ownerPlayerId());
        var config=MineAgentRuntimeServices.config(server);
        if(player==null||!context.owner().equals(job.ownerPlayerId())||!context.agent().equals(job.agentId())
                ||!ServerAuthority.allowed(player,PermissionAction.RUN_CODE)||!ServerAuthority.allowed(player,PermissionAction.MANAGE_PACKAGES)
                ||context.runGeneration()!=config.permissionGeneration(context.owner(),PermissionAction.RUN_CODE)
                ||context.manageGeneration()!=config.permissionGeneration(context.owner(),PermissionAction.MANAGE_PACKAGES)) {
            save(job.operationId(),Map.of("status","PUBLISHED_NOT_ACTIVATED","error","GENERATION_ACTIVATION_AUTHORITY_CHANGED","executed",false));return;
        }
        var pack=packages.worldLibrary().get(original.packageId()).orElseThrow();
        if(pack.enabled()||pack.revision()!=original.revision()||!pack.canonicalSha256().equals(original.canonicalSha256()))throw new IllegalStateException("GENERATED_ACTIVATION_SOURCE_CHANGED");
        String compatibility=packages.nativeCompatibility().check(pack,player.getUUID());
        if(!compatibility.isEmpty()){save(job.operationId(),Map.of("status","PUBLISHED_NOT_ACTIVATED","error",compatibility,"executed",false));return;}
        // A persistent STARTED result exists before any lifecycle operation. A crash never silently retries it.
        save(job.operationId(),Map.of("status","ACTIVATION_STARTED","mode",pack.activationMode(),"executed",false,"replayAllowed",false));
        try {
            switch(pack.activationMode()) {
                case HOT_RUNTIME -> {
                    var runtime=WorldContentRuntime.get(server);var receipts=new ArrayList<Object>();boolean active=!pack.definitions().isEmpty();
                    for(var definition:pack.definitions().values().stream().sorted(Comparator.comparing(d->d.definitionId().toString())).toList()) {
                        var head=packages.worldLibrary().get(pack.packageId()).orElseThrow();
                        var result=runtime.activate(player,operation(job.operationId(),definition.definitionId().toString()),head.packageId(),head.revision(),definition.definitionId(),context.anchor(),true,false);
                        receipts.add(result);if(!result.state().equals("ACTIVE")){active=false;break;}
                    }
                    save(job.operationId(),Map.of("status",active?"PUBLISHED_AND_ACTIVE":"PUBLISHED_ACTIVATION_INCOMPLETE","activations",receipts,"executed",!receipts.isEmpty(),"replayAllowed",false));
                }
                case DATA_RELOAD, WORLD_REOPEN -> {
                    var runtime=NativeDataPackRuntime.get(server);
                    var observed=runtime.read(player,Map.of("packageId",pack.packageId().toString(),"packageRevision",Long.toString(pack.revision()),"offset","0","operationId",""));
                    UUID action=operation(job.operationId(),"data");
                    var arguments=Map.of("packageId",pack.packageId().toString(),"packageRevision",Long.toString(pack.revision()),"canonical",pack.canonicalSha256(),
                            "action",pack.activationMode()==ActivationMode.WORLD_REOPEN?"ENABLE_AT_REOPEN":"ENABLE","selection",observed.get("selection").toString(),"environment",observed.get("environment").toString(),"confirmed","true");
                    var result=runtime.submit(player,action,arguments);
                    save(job.operationId(),Map.of("status","PUBLISHED_LIFECYCLE_SUBMITTED","mode",pack.activationMode(),"operationId",action,"receipt",result,"executed",true,"replayAllowed",false));
                }
                case RESOURCE_RELOAD -> {
                    var item=new ClientResource(job.operationId(),job.ownerPlayerId(),pack.packageId(),pack.revision(),pack.canonicalSha256(),context.runGeneration(),context.manageGeneration());
                    if(!db.compareAndSet(world,CLIENT_QUEUE,item.operation().toString(),0,json.writeValueAsString(item),System.currentTimeMillis()).accepted())throw new IllegalStateException("GENERATED_RESOURCE_QUEUE_CONFLICT");
                    clientQueue.put(item.operation(),item);
                    save(job.operationId(),Map.of("status","PUBLISHED_AWAITING_CLIENT_RESOURCE","mode",pack.activationMode(),"executed",false));
                }
                case BOOT_EXTENSION -> save(job.operationId(),Map.of("status","PUBLISHED_AWAITING_BOOT_PIPELINE","mode",pack.activationMode(),"executed",false));
                default -> save(job.operationId(),Map.of("status","PUBLISHED_LIFECYCLE_REQUIRED","mode",pack.activationMode(),"executed",false));
            }
        } catch(Exception failure) {
            save(job.operationId(),Map.of("status","PUBLISHED_ACTIVATION_UNVERIFIED","error",Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()),"executed","UNKNOWN","replayAllowed",false));
        }
    }
    Map<String,Object> inspect(ServerPlayer player,PackageGenerationJob job)throws Exception {
        if(!player.getUUID().equals(job.ownerPlayerId()))throw new SecurityException("GENERATED_ACTIVATION_OWNER");
        var row=db.get(world,RESULT,job.operationId().toString());
        if(row.isEmpty())return Map.of("status","PUBLISHED_LIFECYCLE_REQUIRED","executed",false);
        @SuppressWarnings("unchecked") var result=new LinkedHashMap<String,Object>(json.readValue(row.get().payload(),Map.class));
        if("PUBLISHED_LIFECYCLE_SUBMITTED".equals(result.get("status"))) {
            var head=packages.worldLibrary().get(job.packageId()).orElseThrow();
            result.put("observation",NativeDataPackRuntime.get(server).read(player,Map.of("packageId",head.packageId().toString(),"packageRevision",Long.toString(head.revision()),"offset","0","operationId",result.get("operationId").toString())));
        }
        return Map.copyOf(result);
    }
    Map<String,Object> clientResources(ServerPlayer player)throws Exception{
        var result=new ArrayList<ClientResource>();var config=MineAgentRuntimeServices.config(server);
        for(var item:List.copyOf(clientQueue.values()))if(item.owner().equals(player.getUUID())){
            var pack=packages.worldLibrary().get(item.packageId()).orElse(null);
            if(!ServerAuthority.allowed(player,PermissionAction.RUN_CODE)||!ServerAuthority.allowed(player,PermissionAction.MANAGE_PACKAGES)
                    ||item.runGeneration()!=config.permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE)||item.manageGeneration()!=config.permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES)
                    ||pack==null||pack.revision()!=item.revision()||!pack.canonicalSha256().equals(item.canonical())){
                completeClient(item,"FAILED","GENERATED_RESOURCE_AUTHORITY_OR_SOURCE_CHANGED");continue;
            }
            result.add(item);if(result.size()==8)break;
        }
        return Map.of("requests",result,"scope","CURRENT_PLAYER_GENERATED_RESOURCE_PACKS");
    }
    Map<String,Object> clientResult(ServerPlayer player,UUID operation,String canonical,String status,String code)throws Exception{
        var item=clientQueue.get(operation);if(item==null)return Map.of("status","ALREADY_RECORDED");
        if(!item.owner().equals(player.getUUID())||!item.canonical().equals(canonical)||!Set.of("APPLIED","FAILED","UNKNOWN").contains(status)||code.length()>128)throw new SecurityException("GENERATED_RESOURCE_RECEIPT_SCOPE");
        completeClient(item,status,code);return Map.of("status","RECORDED");
    }
    Map<String,Object> authorizeClient(ServerPlayer player,UUID operation,String canonical)throws Exception{
        var item=clientQueue.get(operation);if(item==null)return Map.of("allowed",false);
        if(!item.owner().equals(player.getUUID())||!item.canonical().equals(canonical))throw new SecurityException("GENERATED_RESOURCE_RECEIPT_SCOPE");
        clientResources(player);return Map.of("allowed",clientQueue.containsKey(operation));
    }
    void cancelClient(UUID owner,UUID operation)throws Exception{var item=clientQueue.get(operation);if(item!=null){if(!item.owner().equals(owner))throw new SecurityException("GENERATED_RESOURCE_RECEIPT_SCOPE");completeClient(item,"CANCELLED","USER_CANCELLED_BEFORE_CLIENT_RECEIPT");}}
    private void completeClient(ClientResource item,String status,String code)throws Exception{
        save(item.operation(),Map.of("status",status.equals("APPLIED")?"PUBLISHED_CLIENT_ACTIVE":"PUBLISHED_CLIENT_"+status,"code",code,"observationSource","OWNER_CLIENT_RESOURCE_LIFECYCLE","executed",status.equals("APPLIED"),"replayAllowed",false));
        var row=db.get(world,CLIENT_QUEUE,item.operation().toString());if(row.isPresent()&&!db.delete(world,CLIENT_QUEUE,item.operation().toString(),row.get().revision(),System.currentTimeMillis()).accepted())throw new IllegalStateException("GENERATED_RESOURCE_RECEIPT_CONFLICT");clientQueue.remove(item.operation());
    }
    public void close()throws Exception {db.close();}
}
