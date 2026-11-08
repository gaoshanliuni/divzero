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
    record Context(UUID owner,UUID agent,RuntimeInstanceLocation anchor,long runGeneration,long manageGeneration){}
    GeneratedContentActivation(MinecraftServer server,ServerPackageRuntime packages)throws Exception {
        this.server=server;this.packages=packages;world=MineAgentRuntimeServices.worldId(server);
        db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));
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
                case RESOURCE_RELOAD -> save(job.operationId(),Map.of("status","PUBLISHED_AWAITING_CLIENT_RESOURCE","mode",pack.activationMode(),"executed",false));
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
    public void close()throws Exception {db.close();}
}
