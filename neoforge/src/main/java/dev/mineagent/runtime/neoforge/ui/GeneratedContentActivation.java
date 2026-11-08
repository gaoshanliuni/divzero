package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.PackageGenerationJob;
import dev.mineagent.runtime.core.persistence.*;
import dev.mineagent.runtime.core.boot.BootActivationStep;
import dev.mineagent.runtime.neoforge.boot.NativeBootRuntime;
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
    private static final String BOOT_QUEUE="generated_boot_pending";
    record BootRequest(UUID operation,Context context,UUID packageId,long revision,String canonical,UUID build,UUID install,BootActivationStep.Stage stage) {
        BootRequest stage(BootActivationStep.Stage next){return new BootRequest(operation,context,packageId,revision,canonical,build,install,next);}
    }
    private final Map<UUID,BootRequest> bootQueue=new LinkedHashMap<>();
    private volatile boolean closed;
    GeneratedContentActivation(MinecraftServer server,ServerPackageRuntime packages)throws Exception {
        this.server=server;this.packages=packages;world=MineAgentRuntimeServices.worldId(server);
        db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));
        for(var row:db.list(world,CLIENT_QUEUE)){var item=json.readValue(row.payload(),ClientResource.class);clientQueue.put(item.operation(),item);}
        for(var row:db.list(world,BOOT_QUEUE)){var item=json.readValue(row.payload(),BootRequest.class);bootQueue.put(item.operation(),item);}
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
                case BOOT_EXTENSION -> {
                    var item=new BootRequest(job.operationId(),context,pack.packageId(),pack.revision(),pack.canonicalSha256(),operation(job.operationId(),"boot-build"),operation(job.operationId(),"boot-install"),BootActivationStep.Stage.QUEUED);
                    saveBoot(item);
                    save(job.operationId(),Map.of("status","PUBLISHED_BOOT_QUEUED","mode",pack.activationMode(),"buildOperation",item.build(),"installOperation",item.install(),"executed",false));
                }
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
        var boot=bootQueue.get(job.operationId());
        if(boot!=null){result.put("status","PUBLISHED_BOOT_"+boot.stage());result.put("buildOperation",boot.build());result.put("installOperation",boot.install());result.put("activeNow",false);}
        if("PUBLISHED_LIFECYCLE_SUBMITTED".equals(result.get("status"))) {
            var head=packages.worldLibrary().get(job.packageId()).orElseThrow();
            var observed=NativeDataPackRuntime.get(server).read(player,Map.of("packageId",head.packageId().toString(),"packageRevision",Long.toString(head.revision()),"offset","0","operationId",result.get("operationId").toString()));result.put("observation",observed);
            var nativeJob=json.valueToTree(observed.get("job"));String phase=nativeJob.path("phase").asText();
            if(phase.equals("COMPLETED")){result.put("status",Boolean.TRUE.equals(observed.get("loadedNow"))?"PUBLISHED_AND_ACTIVE":"PUBLISHED_LIFECYCLE_COMPLETED");result.put("activeNow",observed.get("loadedNow"));}
            else if(phase.equals("AWAITING_REOPEN")){result.put("status","PUBLISHED_READY_AT_WORLD_REOPEN");result.put("activeNow",false);}
            else if(Set.of("FAILED","UNKNOWN").contains(phase)){result.put("status","PUBLISHED_ACTIVATION_"+phase);result.put("executed",phase.equals("UNKNOWN")?"UNKNOWN":false);result.put("error",nativeJob.path("code").asText());}
        }
        return Map.copyOf(result);
    }
    private boolean validClient(ServerPlayer player,ClientResource item){
        var config=MineAgentRuntimeServices.config(server);var pack=packages.worldLibrary().get(item.packageId()).orElse(null);
        return item.owner().equals(player.getUUID())&&ServerAuthority.allowed(player,PermissionAction.RUN_CODE)&&ServerAuthority.allowed(player,PermissionAction.MANAGE_PACKAGES)
                &&item.runGeneration()==config.permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE)&&item.manageGeneration()==config.permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES)
                &&pack!=null&&pack.revision()==item.revision()&&pack.canonicalSha256().equals(item.canonical());
    }
    Map<String,Object> clientResources(ServerPlayer player)throws Exception{
        var result=new ArrayList<ClientResource>();
        for(var item:List.copyOf(clientQueue.values()))if(item.owner().equals(player.getUUID())){
            if(!validClient(player,item)){completeClient(item,"UNKNOWN","GENERATED_RESOURCE_AUTHORITY_OR_SOURCE_CHANGED");continue;}
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
        if(!validClient(player,item)){completeClient(item,"UNKNOWN","GENERATED_RESOURCE_AUTHORITY_OR_SOURCE_CHANGED");return Map.of("allowed",false);}return Map.of("allowed",true);
    }
    void cancelClient(UUID owner,UUID operation)throws Exception{
        var item=clientQueue.get(operation);if(item!=null){if(!item.owner().equals(owner))throw new SecurityException("GENERATED_RESOURCE_RECEIPT_SCOPE");completeClient(item,"UNKNOWN","USER_CANCELLED_BEFORE_CLIENT_RECEIPT");}
        var boot=bootQueue.get(operation);if(boot!=null){if(!boot.context().owner().equals(owner))throw new SecurityException("BOOT_BUILD_OWNER");completeBoot(boot,boot.stage()==BootActivationStep.Stage.QUEUED?"CANCELLED":"UNKNOWN","BOOT_AUTOMATIC_CANCELLED",List.of());}
    }
    private void completeClient(ClientResource item,String status,String code)throws Exception{
        save(item.operation(),Map.of("status",status.equals("APPLIED")?"PUBLISHED_CLIENT_ACTIVE":"PUBLISHED_CLIENT_"+status,"code",code,"observationSource","OWNER_CLIENT_RESOURCE_LIFECYCLE","executed",status.equals("UNKNOWN")?"UNKNOWN":status.equals("APPLIED"),"replayAllowed",false));
        var row=db.get(world,CLIENT_QUEUE,item.operation().toString());if(row.isPresent()&&!db.delete(world,CLIENT_QUEUE,item.operation().toString(),row.get().revision(),System.currentTimeMillis()).accepted())throw new IllegalStateException("GENERATED_RESOURCE_RECEIPT_CONFLICT");clientQueue.remove(item.operation());
    }
    private void saveBoot(BootRequest item)throws Exception {
        var old=db.get(world,BOOT_QUEUE,item.operation().toString());
        if(!db.compareAndSet(world,BOOT_QUEUE,item.operation().toString(),old.map(RuntimeRecord::revision).orElse(0L),json.writeValueAsString(item),System.currentTimeMillis()).accepted())throw new IllegalStateException("BOOT_AUTOMATIC_QUEUE_CONFLICT");
        bootQueue.put(item.operation(),item);
    }
    private boolean validBoot(BootRequest item) {
        var player=server.getPlayerList().getPlayer(item.context().owner());if(player==null)return false;
        try{NativeBootRuntime.authorize(player);}catch(SecurityException denied){return false;}
        var config=MineAgentRuntimeServices.config(server);var pack=packages.ownedPackage(player.getUUID(),item.packageId(),item.revision()).orElse(null);
        return pack!=null&&pack.revision()==item.revision()&&pack.canonicalSha256().equals(item.canonical())
                &&item.context().runGeneration()==config.permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE)
                &&item.context().manageGeneration()==config.permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES);
    }
    private boolean bootPermit(BootRequest item) {
        if(closed)return false;
        java.util.function.Supplier<Boolean> check=()->!closed&&bootQueue.containsKey(item.operation())&&validBoot(item);
        try{return server.isSameThread()?check.get():server.submit(check).get(3,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception unavailable){return false;}
    }
    /** One FIFO step per Tick. UI lifetime and ordinary dimension changes do not own this queue. */
    void tick() {
        if(closed||bootQueue.isEmpty())return;
        var item=bootQueue.values().iterator().next();bootQueue.remove(item.operation());bootQueue.put(item.operation(),item);
        try {
            // A finished receipt saved immediately before a crash takes precedence over a stale queue row.
            var saved=db.get(world,RESULT,item.operation().toString());
            if(saved.isPresent()&&json.readTree(saved.get().payload()).path("bootTerminal").asBoolean()){removeBoot(item);return;}
            var runtime=NativeBootRuntime.get(server);var build=runtime.generatedBuild(item.context().owner(),item.build()).orElse(null);
            var action=BootActivationStep.next(item.stage(),build==null?"":build.phase(),runtime.busy());
            if(build!=null&&(!build.manifest().packageId().equals(item.packageId())||build.manifest().revision()!=item.revision()||!build.manifest().canonicalSha256().equals(item.canonical())))throw new IllegalStateException("BOOT_AUTOMATIC_SOURCE_CHANGED");
            if(action==BootActivationStep.Action.READY_AT_RESTART){completeBoot(item,"READY_AT_SERVER_RESTART","",build.diagnostics());return;}
            if(action==BootActivationStep.Action.FAILED||action==BootActivationStep.Action.UNKNOWN){completeBoot(item,action.name(),build==null?"BOOT_SUBMISSION_RECEIPT_MISSING":build.error().isEmpty()?"BOOT_RESULT_UNVERIFIED":build.error(),build==null?List.of():build.diagnostics());return;}
            var player=server.getPlayerList().getPlayer(item.context().owner());
            if(player==null&&item.stage()==BootActivationStep.Stage.QUEUED)return;
            if(!validBoot(item)){completeBoot(item,item.stage()==BootActivationStep.Stage.QUEUED?"NOT_STARTED":"UNKNOWN","BOOT_AUTHORITY_OR_SOURCE_CHANGED",List.of());return;}
            if(action==BootActivationStep.Action.WAIT)return;
            String environment=NativePackageCompatibility.observe().fingerprint();
            if(action==BootActivationStep.Action.BUILD){
                saveBoot(item.stage(BootActivationStep.Stage.BUILD_SUBMITTED));
                runtime.build(player,item.build(),Map.of("kind","build","packageId",item.packageId().toString(),"packageRevision",Long.toString(item.revision()),"canonical",item.canonical(),"environment",environment),()->bootPermit(item));
            }else if(action==BootActivationStep.Action.INSTALL){
                saveBoot(item.stage(BootActivationStep.Stage.INSTALL_SUBMITTED));
                runtime.change(player,item.install(),Map.of("kind","change","buildId",item.build().toString(),"expectedRevision",Long.toString(build.revision()),"action","INSTALL","environment",environment,"confirmed","true","confirmModId",build.modId()),()->bootPermit(item));
            }
        }catch(Exception failure){
            try{completeBoot(bootQueue.getOrDefault(item.operation(),item),"UNKNOWN",NativeBootRuntime.code(failure),List.of());}
            catch(Exception persistence){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Automatic BOOT receipt pending operation={} cause={}",item.operation(),persistence.getClass().getSimpleName());}
        }
    }
    private void completeBoot(BootRequest item,String status,String error,List<String> diagnostics)throws Exception {
        save(item.operation(),Map.ofEntries(Map.entry("status","PUBLISHED_BOOT_"+status),Map.entry("mode",ActivationMode.BOOT_EXTENSION),Map.entry("bootTerminal",true),Map.entry("buildOperation",item.build()),Map.entry("installOperation",item.install()),Map.entry("activeNow",false),Map.entry("error",error),Map.entry("diagnostics",diagnostics),Map.entry("executed",status.equals("UNKNOWN")?"UNKNOWN":status.equals("READY_AT_SERVER_RESTART")),Map.entry("replayAllowed",false)));
        removeBoot(item);
    }
    private void removeBoot(BootRequest item)throws Exception {
        var row=db.get(world,BOOT_QUEUE,item.operation().toString());if(row.isPresent()&&!db.delete(world,BOOT_QUEUE,item.operation().toString(),row.get().revision(),System.currentTimeMillis()).accepted())throw new IllegalStateException("BOOT_AUTOMATIC_RECEIPT_CONFLICT");bootQueue.remove(item.operation());
    }
    public void close()throws Exception {closed=true;db.close();}
}
