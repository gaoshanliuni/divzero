package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.time.Clock;
import java.util.*;

/** Production package entry, shared by trusted UI and future natural-language tool dispatch. No GUI classes. */
public final class ServerPackageRuntime implements AutoCloseable {
    private static final Map<MinecraftServer, ServerPackageRuntime> RUNTIMES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final RuntimePackageLibrary library;
    private final dev.mineagent.runtime.neoforge.content.NativePackageCompatibility nativeCompatibility;
    public dev.mineagent.runtime.neoforge.content.NativePackageCompatibility nativeCompatibility(){requireServerThread();return nativeCompatibility;}
    private final dev.mineagent.runtime.core.compile.NativeKnowledgeStore nativeKnowledge;
    private final ContentAddressedStore content;
    private final PackageGenerationService jobs;
    private final PackageUiPatchService patches;
    private final PackageUiPatchService worldPatches;
    private final java.util.concurrent.ConcurrentHashMap<UUID,java.util.concurrent.atomic.AtomicBoolean> worldPatchPermits=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID,java.util.concurrent.atomic.AtomicBoolean> generationPermits=new java.util.concurrent.ConcurrentHashMap<>();
    private final AutoCloseable worldPatchTaskChanges;
    private final ExplicitPackageImports imports;
    private record CandidateOffer(UUID owner,UUID operation,long expires){}
    private final Map<UUID,CandidateOffer> candidateOffers=new HashMap<>();
    private final PackageTransferLeases transfers = new PackageTransferLeases(Clock.systemUTC());
    private final Set<UUID> preparing = new HashSet<>();
    private final PackageTransferLeases resourceTransfers=new PackageTransferLeases(Clock.systemUTC(),ResourcePackPlan.MAX_BUNDLE);
    private final Set<UUID> resourcePreparing=new HashSet<>();
    private final PackageTransferLeases clientScriptTransfers=new PackageTransferLeases(Clock.systemUTC(),ClientScriptPlan.MAX_BUNDLE);
    private final Set<UUID> clientScriptPreparing=new HashSet<>();
    private final PackageTransferLeases clientJavaTransfers=new PackageTransferLeases(Clock.systemUTC(),ClientJavaPlan.MAX_BUNDLE);
    private final Set<UUID> clientJavaPreparing=new HashSet<>();
    private final java.util.concurrent.ExecutorService io = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4), r -> {
                Thread t = new Thread(r, "mineagent-package-transfer"); t.setDaemon(true); return t;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private final Set<UUID> rebuildingUi=new HashSet<>();
    private ServerPackageRuntime(MinecraftServer server) throws Exception {
        this.server = server;
        var directory = server.getServerDirectory().resolve("mineagent-runtime-data");
        content = new ContentAddressedStore(directory.resolve("content"));
        library = RuntimePackageLibrary.open(directory.resolve("runtime.db"), Clock.systemUTC(),
                MineAgentRuntimeServices.identity(server).publicKeyEncoded(), content);
        var javaDependencies=MineAgentRuntimeServices.javaExtensions(server);
        library.bindMutationGuard(id->{javaDependencies.requirePackageMutable(id);StudioScriptRuntime.requirePackageMutable(server,id);});
        try { jobs = PackageGenerationService.open(directory.resolve("runtime.db"), MineAgentRuntimeServices.worldId(server),
                Clock.systemUTC(), MineAgentRuntimeServices.tasks(server), library); }
        catch (Exception failure) { library.close(); throw failure; }
        try{patches=PackageUiPatchService.open(directory.resolve("runtime.db"),MineAgentRuntimeServices.worldId(server),Clock.systemUTC(),MineAgentRuntimeServices.tasks(server),library);}
        catch(Exception failure){jobs.close();library.close();throw failure;}
        try{worldPatches=PackageUiPatchService.openWorld(directory.resolve("runtime.db"),MineAgentRuntimeServices.worldId(server),Clock.systemUTC(),MineAgentRuntimeServices.tasks(server),library);}catch(Exception failure){patches.close();jobs.close();library.close();throw failure;}
        try{imports=ExplicitPackageImports.open(directory.resolve("runtime.db"),library);}catch(Exception failure){worldPatches.close();patches.close();jobs.close();library.close();throw failure;}
        try{nativeCompatibility=new dev.mineagent.runtime.neoforge.content.NativePackageCompatibility(server);}catch(Exception failure){imports.close();worldPatches.close();patches.close();jobs.close();library.close();throw failure;}
        try{nativeKnowledge=dev.mineagent.runtime.core.compile.NativeKnowledgeStore.open(directory.resolve("runtime.db"),MineAgentRuntimeServices.worldId(server),Clock.systemUTC());}catch(Exception failure){nativeCompatibility.close();imports.close();worldPatches.close();patches.close();jobs.close();library.close();throw failure;}
        worldPatchTaskChanges=MineAgentRuntimeServices.tasks(server).onChange(task->{var permit=worldPatchPermits.get(task.taskId());if(permit!=null)permit.set(false);var generation=generationPermits.get(task.taskId());if(generation!=null)generation.set(false);});
    }
    public static synchronized ServerPackageRuntime get(MinecraftServer server) {
        var existing = RUNTIMES.get(server); if (existing != null) return existing;
        try { var created = new ServerPackageRuntime(server); RUNTIMES.put(server, created); return created; }
        catch (Exception failure) { throw new IllegalStateException("PACKAGE_STORE_UNAVAILABLE", failure); }
    }
    public static synchronized void stop(MinecraftServer server) {
        var runtime = RUNTIMES.remove(server);
        if (runtime != null) try { runtime.close(); } catch (Exception failure) {
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Package runtime close failed: {}", failure.getClass().getSimpleName());
        }
    }
    public dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs.Page<?> history(UUID owner,String category,String state,String archive,int offset){
        requireServerThread();return switch(category){
            case "GENERATION"->jobs.history(owner,state,archive,offset);case "UI_PATCH"->patches.history(owner,state,archive,offset);
            case "WORLD_PATCH"->worldPatches.history(owner,state,archive,offset);case "LINK"->MineAgentRuntimeServices.agentUiLinks(server).history(owner,state,archive,offset);
            default->throw new IllegalArgumentException("PACKAGE_HISTORY_CATEGORY");
        };
    }
    public Object historyRecord(UUID owner,String category,UUID operation){
        requireServerThread();return switch(category){
            case "GENERATION"->jobs.find(owner,operation).orElseThrow(()->new SecurityException("PACKAGE_HISTORY_NOT_OWNED"));
            case "UI_PATCH"->patches.get(owner,operation);case "WORLD_PATCH"->worldPatches.get(owner,operation);
            case "LINK"->MineAgentRuntimeServices.agentUiLinks(server).operation(operation).filter(l->l.owner().equals(owner)).orElseThrow(()->new SecurityException("PACKAGE_HISTORY_NOT_OWNED"));
            default->throw new IllegalArgumentException("PACKAGE_HISTORY_CATEGORY");
        };
    }
    public List<PackageGenerationJob> list(UUID owner) { requireServerThread(); return jobs.list(owner); }
    public Optional<PackageGenerationJob> generation(UUID owner,UUID operation){requireServerThread();return jobs.find(owner,operation);}
    public Optional<PackageUiPatchJob> patchJob(UUID owner,UUID operation){requireServerThread();return patches.find(owner,operation);}
    public Optional<PackageUiPatchService.RebuildEvidence> uiRebuildEvidence(UUID owner,UUID operation){requireServerThread();return patches.rebuildEvidence(owner,operation);}
    public boolean mayRebuildUi(ServerPlayer viewer,UUID operation){requireServerThread();try{var j=patches.get(viewer.getUUID(),operation);return viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)&&allowed(viewer.getUUID(),j.agentId());}catch(SecurityException denied){return false;}}
    public java.util.concurrent.CompletableFuture<PackageUiPatchJob> rebuildUi(ServerPlayer viewer,UUID operation,String rawHash,boolean confirmed){
        requireServerThread();if(!confirmed||!mayRebuildUi(viewer,operation))throw new SecurityException("UI_PATCH_REBUILD_OPERATOR_REQUIRED");
        if(rawHash==null||!rawHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("UI_PATCH_REBUILD_HASH");
        var job=patches.get(viewer.getUUID(),operation);if(!job.rawOutputSha256().isEmpty()&&!job.rawOutputSha256().equals(rawHash))throw new IllegalArgumentException("UI_PATCH_REBUILD_CHANGED");
        if(rebuildingUi.size()>=4||!rebuildingUi.add(operation))throw new IllegalStateException("UI_PATCH_REBUILD_BUSY");
        var signer=MineAgentRuntimeServices.identity(server);var result=new java.util.concurrent.CompletableFuture<PackageUiPatchJob>();
        try{io.execute(()->{
            dev.mineagent.runtime.worker.generation.WorkerUiPatchResult built=null;Throwable failure=null;
            try{if(java.nio.file.Files.size(content.pathFor(rawHash))>4*1024*1024)throw new IllegalArgumentException("UI_PATCH_OUTPUT_LIMIT");var raw=new String(content.read(rawHash),java.nio.charset.StandardCharsets.UTF_8);built=dev.mineagent.runtime.worker.generation.WorkerUiPatchResult.prepare(job.base(),raw,"operator-attested-output",content,signer);if(built.candidate()==null)throw new IllegalStateException(built.errorCode());}
            catch(Exception|LinkageError error){failure=error;}
            var candidate=built;var error=failure;server.execute(()->{rebuildingUi.remove(operation);try{
                if(closed)throw new IllegalStateException("PACKAGE_RUNTIME_UNAVAILABLE");if(error!=null)throw new IllegalStateException("UI_PATCH_REBUILD_FAILED",error);
                if(!mayRebuildUi(viewer,operation)||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("UI_PATCH_REBUILD_OPERATOR_REQUIRED");
                result.complete(patches.rebuildFailed(viewer.getUUID(),operation,candidate.candidate(),rawHash,true));
            }catch(Exception failed){result.completeExceptionally(failed);}});
        });}catch(RuntimeException rejected){rebuildingUi.remove(operation);result.completeExceptionally(rejected);}return result;
    }
    public Optional<PackageUiPatchJob> worldPatchJob(UUID owner,UUID operation){requireServerThread();return worldPatches.find(owner,operation);}
    public List<PackageUiPatchJob> worldPatchJobs(UUID owner){requireServerThread();return worldPatches.list(owner);}
    public Optional<RuntimePackage> worldVersion(UUID id,String hash)throws Exception{requireServerThread();var current=library.get(id).filter(p->p.canonicalSha256().equals(hash));if(current.isPresent())return current;var world=worldPatches.version(id,hash);return world.isPresent()?world:patches.version(id,hash);}
    public boolean mayWorldPatch(UUID owner,UUID agent){requireServerThread();var viewer=server.getPlayerList().getPlayer(owner);boolean op=viewer!=null&&viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);var permissions=MineAgentRuntimeServices.permissions(server);return allowed(owner,agent)&&permissions.allowed(owner,op,PermissionAction.RUN_CODE)&&permissions.allowed(owner,op,PermissionAction.MANAGE_PACKAGES);}
    private boolean worldPatchCurrent(PackageUiPatchJob j){var t=MineAgentRuntimeServices.tasks(server).get(j.taskId()).orElse(null);return !closed&&t!=null&&t.intentRevision()==j.taskIntentRevision()&&t.status()==dev.mineagent.runtime.api.task.TaskStatus.RUNNING&&mayWorldPatch(j.ownerPlayerId(),j.agentId())&&library.get(j.base().packageId()).filter(p->p.revision()==j.base().revision()&&p.canonicalSha256().equals(j.base().canonicalSha256())).isPresent();}
    public PackageUiPatchService.Submission worldPatch(ServerPlayer viewer,UUID agent,UUID operation,UUID packageId,long revision,String prompt)throws Exception{
        requireServerThread();if(!mayWorldPatch(viewer.getUUID(),agent))throw new SecurityException("PERMISSION_DENIED");
        var old=worldPatches.find(viewer.getUUID(),operation).orElse(null);var base=old==null?ownedPackage(viewer.getUUID(),packageId,revision).orElseThrow(()->new SecurityException("PACKAGE_NOT_OWNED")):old.base();
        if(!base.packageId().equals(packageId)||base.revision()!=revision)throw new IllegalStateException("STALE_PACKAGE");
        var submitted=worldPatches.submit(viewer.getUUID(),agent,operation,base,prompt,true);if(submitted.duplicate())return submitted;
        var job=submitted.job();var permit=new java.util.concurrent.atomic.AtomicBoolean(true);worldPatchPermits.put(job.taskId(),permit);
        java.util.function.BooleanSupplier dispatch=()->{if(!permit.get()||closed)return false;try{return server.submit(()->permit.get()&&worldPatchCurrent(job)).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){return false;}};
        MineAgentRuntimeServices.worker(server).generateWorldPatch(MineAgentRuntimeServices.config(server),job,MineAgentRuntimeServices.identity(server),dispatch).whenComplete((result,error)->server.execute(()->{
            worldPatchPermits.remove(job.taskId(),permit);if(closed)return;
            try{
                if(error!=null||result==null)worldPatches.fail(job,"WORLD_PATCH_GENERATION_FAILED");
                else if(!result.errorCode().isEmpty())worldPatches.failed(job,result.errorCode(),result.providerId(),result.rawOutputSha256());
                else if(!permit.get()||!worldPatchCurrent(job))worldPatches.failed(job,"WORLD_PATCH_STALE_OR_REVOKED",result.providerId(),result.rawOutputSha256());
                else worldPatches.ready(job,result.candidate(),result.providerId(),result.rawOutputSha256(),true);
                        }catch(Exception e){try{worldPatches.fail(job,"WORLD_PATCH_COMMIT_FAILED");}catch(Exception ignored){}}
            finally{if(result!=null&&!result.rawOutputSha256().isEmpty())try{worldPatches.recordOutputEvidence(job,result.providerId(),result.rawOutputSha256());}catch(Exception rejected){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("World patch raw evidence not attached operation={} code={}",job.operationId(),rejected.getClass().getSimpleName());}}
        }));return submitted;
    }
    public PackageUiPatchJob worldPatchAction(UUID owner,UUID operation,String action,boolean confirmed,String expectedHash)throws Exception{
        requireServerThread();var job=worldPatches.get(owner,operation);if(!mayWorldPatch(owner,job.agentId()))throw new SecurityException("PERMISSION_DENIED");
        if(action.equals("cancel")){var permit=worldPatchPermits.get(job.taskId());if(permit!=null)permit.set(false);return worldPatches.cancel(owner,operation);}
        var target=action.equals("apply")?job.candidate():job.base();if(!confirmed||target==null||!target.canonicalSha256().equals(expectedHash))throw new SecurityException("WORLD_PATCH_CONFIRMATION_REQUIRED");
        var result=switch(action){case "apply"->worldPatches.apply(owner,operation,true);case "rollback"->worldPatches.rollback(owner,operation,true);default->throw new IllegalArgumentException("WORLD_PATCH_ACTION");};
        if(!job.state().equals(result.state())&&Set.of("APPLIED","ROLLED_BACK").contains(result.state())){
            ServerUiRuntime.get(server).packageChanged(job.base().packageId(),result.headRevision());
            MineAgentRuntimeServices.audit(server).record(owner.toString(),"WORLD_PACKAGE_REVISION",job.base().packageId().toString(),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("operation",operation,"action",action,"baseHash",job.base().canonicalSha256(),"targetHash",target.canonicalSha256(),"revision",result.headRevision(),"nativeExecuted",false)));
        }return result;
    }
    public Map<String,String> inspectWorldPatch(UUID owner,UUID operation,int offset,String path,int sourceOffset,boolean before)throws Exception{
        requireServerThread();var job=worldPatches.get(owner,operation);if(!mayWorldPatch(owner,job.agentId()))throw new SecurityException("PERMISSION_DENIED");
        if(offset<0||offset>256||sourceOffset<0||sourceOffset>1_048_576)throw new IllegalArgumentException("WORLD_PATCH_OFFSET");
        var next=job.candidate()==null?job.base():job.candidate();var all=new TreeSet<String>();all.addAll(job.base().resources().keySet());all.addAll(next.resources().keySet());all.removeIf(p->p.startsWith("ui/"));
        var files=all.stream().skip(offset).limit(16).map(p->{var a=job.base().resources().get(p);var b=next.resources().get(p);return Map.of("path",p,"beforeHash",a==null?"":a.sha256(),"afterHash",b==null?"":b.sha256(),"changed",!Objects.equals(a,b));}).toList();
        var meta=new LinkedHashMap<String,Object>();meta.put("activationMode",job.base().activationMode().name());meta.put("operationId",operation);meta.put("state",job.state());meta.put("baseRevision",job.base().revision());meta.put("baseHash",job.base().canonicalSha256());meta.put("candidateRevision",next.revision());meta.put("candidateHash",job.candidate()==null?"":job.candidate().canonicalSha256());meta.put("files",files);meta.put("offset",offset);meta.put("more",offset+files.size()<all.size());
        String source="",sha="";int length=0;
        if(path!=null&&!path.isEmpty()){
            if(!all.contains(path))throw new IllegalArgumentException("WORLD_PATCH_SOURCE_PATH");var ref=(before?job.base():next).resources().get(path);
            if(ref!=null){sha=ref.sha256();if(path.endsWith(".java")||path.endsWith(".cfg")||Set.of("application/javascript","text/javascript","application/json","text/plain").contains(ref.mediaType())){
                if(ref.size()>1_048_576)throw new IllegalArgumentException("WORLD_PATCH_SOURCE_BUDGET");byte[] bytes=content.read(sha);if(bytes.length!=ref.size())throw new IllegalStateException("WORLD_PATCH_SOURCE_INTEGRITY");String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);length=text.length();if(sourceOffset>length)throw new IllegalArgumentException("WORLD_PATCH_OFFSET");source=text.substring(sourceOffset,Math.min(length,sourceOffset+8192));
            }else source="[binary resource; inspect declared SHA-256 and Native preview after approval]";}
        }
        return Map.of("summary",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta),"source",source,"sourceSha256",sha,"sourceLength",Integer.toString(length),"sourceOffset",Integer.toString(sourceOffset));
    }
    private dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent budgetParent(UUID operation,String relation){
        requireServerThread();var ui=MineAgentRuntimeServices.agentUiLinks(server).operation(operation).orElse(null);
        var world=MineAgentRuntimeServices.taskExecutor(server).worldActions().generationBudgetParent(operation);
        if(ui!=null&&world!=null)throw new IllegalStateException("TASK_BUDGET_PARENT_AMBIGUOUS");
        return ui!=null?new dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent(ui.parentTaskId(),ui.parentIntent(),relation):world;
    }
    public boolean mayGenerate(UUID owner,UUID agent){requireServerThread();return allowed(owner,agent);}
    private boolean parentCurrent(UUID operation){return MineAgentRuntimeServices.agentUiLinks(server).mayProduce(operation)&&MineAgentRuntimeServices.taskExecutor(server).worldActions().mayProduceWorldPackage(operation);}
    public List<PackageUiPatchJob> patchJobs(UUID owner){requireServerThread();return patches.list(owner);}
    public record OwnedHeadPage(List<RuntimePackage> items,long total,int nextOffset,boolean more){}
    public OwnedHeadPage ownedHeads(UUID owner,int offset,int limit){requireServerThread();if(offset<0||limit<1||limit>32)throw new IllegalArgumentException("PACKAGE_CATALOG_PAGE");try{var page=library.catalog(MineAgentRuntimeServices.worldId(server),owner,"",offset,limit);var items=page.ids().stream().map(library::get).flatMap(Optional::stream).filter(p->ownedPackage(owner,p.packageId(),p.revision()).isPresent()).toList();return new OwnedHeadPage(items,page.total(),page.nextOffset(),page.more());}catch(Exception failure){throw new IllegalStateException("PACKAGE_CATALOG_UNAVAILABLE",failure);}}
    public List<RuntimePackage> heads(UUID owner){requireServerThread();try{return library.catalog(MineAgentRuntimeServices.worldId(server),owner,"",0,32).ids().stream().map(library::get).flatMap(Optional::stream).filter(p->ownedPackage(owner,p.packageId(),p.revision()).isPresent()).toList();}catch(Exception failure){throw new IllegalStateException("PACKAGE_CATALOG_UNAVAILABLE",failure);}}
    private boolean studioOwned(UUID owner,UUID id,long revision){try{var p=library.get(id).orElse(null);var link=library.studioLink(MineAgentRuntimeServices.worldId(server),owner,id);return p!=null&&p.revision()==revision&&link!=null&&link.canonical().equals(p.canonicalSha256())&&link.packageRevision()<=revision;}catch(Exception e){throw new IllegalStateException("JAVA_STUDIO_OWNER_UNAVAILABLE",e);}}
    private boolean copyOwned(UUID owner,UUID id,long revision){try{var p=library.get(id).orElse(null);return p!=null&&p.revision()==revision&&library.copyOwned(MineAgentRuntimeServices.worldId(server),owner,id,revision,p.canonicalSha256());}catch(Exception e){throw new IllegalStateException("PACKAGE_ASSET_OWNER_UNAVAILABLE",e);}}
    public Map<String,Object> headView(UUID owner,RuntimePackage p)throws Exception {requireServerThread();var result=new LinkedHashMap<>(headView(p));var alias=library.alias(owner,p.packageId());result.put("sourceName",p.name());result.put("aliasRevision",alias.revision());if(!alias.name().isEmpty())result.put("name",alias.name());return Map.copyOf(result);}
    public java.util.concurrent.CompletableFuture<RuntimePackage> prepareAssetCopy(RuntimePackage source,UUID id,String name){requireServerThread();var signer=MineAgentRuntimeServices.identity(server);return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{if(closed)throw new IllegalStateException("PACKAGE_RUNTIME_UNAVAILABLE");String error=library.validateCandidate(source);if(!error.isEmpty())throw new IllegalStateException("PACKAGE_ASSET_SOURCE_UNAVAILABLE");return RuntimePackageAssetCopy.prepare(source,id,name,signer);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}},io);}
    public static Map<String,Object> headView(RuntimePackage p){
        return Map.ofEntries(Map.entry("packageId",p.packageId()),Map.entry("name",p.name()),Map.entry("version",p.version()),Map.entry("origin",p.origin()),Map.entry("type",p.type()),Map.entry("enabled",p.enabled()),Map.entry("revision",p.revision()),Map.entry("canonicalSha256",p.canonicalSha256()),Map.entry("uiAvailable",PackageUiEntrypoints.select(p.entrypoints(),false).isPresent()),Map.entry("hudAvailable",PackageUiEntrypoints.select(p.entrypoints(),true).isPresent()),Map.entry("containerAvailable",PackageUiEntrypoints.named(p.entrypoints(),"container").isPresent()),Map.entry("worldAvailable",p.entrypoints().containsKey("server")&&!p.definitions().isEmpty()),Map.entry("dataPackAvailable",Set.of(dev.mineagent.runtime.api.packages.ActivationMode.DATA_RELOAD,dev.mineagent.runtime.api.packages.ActivationMode.WORLD_REOPEN).contains(p.activationMode())),Map.entry("javaAvailable",p.entrypoints().containsKey("java")),Map.entry("scriptAvailable",p.entrypoints().containsKey("studio_script")),Map.entry("clientScriptAvailable",p.entrypoints().containsKey("client")),Map.entry("clientJavaAvailable",p.entrypoints().containsKey("client_java")),Map.entry("bootAvailable",p.activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.BOOT_EXTENSION),Map.entry("resourcePackAvailable",p.activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.RESOURCE_RELOAD),Map.entry("resourceCount",p.resources().size()),Map.entry("state","LOADED_HEAD"));
    }
    public Map<String,Object> operationView(ServerPlayer viewer,String category,UUID operation){
        requireServerThread();var record=historyRecord(viewer.getUUID(),category,operation);var data=new LinkedHashMap<String,Object>();UUID pkg;
        if(record instanceof PackageGenerationJob j){
            pkg=j.packageId();data.put("operationId",j.operationId());data.put("taskId",j.taskId());data.put("agentId",j.agentId());data.put("packageId",pkg);data.put("packageRevision",j.packageRevision());data.put("state",j.state());data.put("errorCode",j.errorCode());data.put("providerId",j.providerId());data.put("updatedAt",j.updatedAtEpochMillis());data.put("purpose",j.purpose());data.put("jobRevision",j.revision());data.put("rawOutputSha256",j.rawOutputSha256());data.put("repairable",j.state().equals("FAILED")&&!j.rawOutputSha256().isEmpty()&&library.get(pkg).isEmpty()&&allowed(viewer.getUUID(),j.agentId()));if(j.repairSource()!=null)data.put("repairOf",j.repairSource().operationId());
        }else if(record instanceof PackageUiPatchJob j){
            pkg=j.base().packageId();data.put("operationId",j.operationId());data.put("taskId",j.taskId());data.put("agentId",j.agentId());data.put("packageId",pkg);data.put("baseRevision",j.base().revision());data.put("candidateRevision",j.base().revision()+1);data.put("headRevision",j.headRevision());data.put("state",j.state());data.put("errorCode",j.errorCode());data.put("rawOutputSha256",j.rawOutputSha256());data.put("baseHash",j.base().canonicalSha256());data.put("candidateHash",j.candidate()==null?"":j.candidate().canonicalSha256());data.put("prompt",j.prompt().substring(0,j.prompt().offsetByCodePoints(0,Math.min(256,j.prompt().codePointCount(0,j.prompt().length())))));data.put("rebuildAllowed",category.equals("UI_PATCH")&&j.state().equals("FAILED")&&mayRebuildUi(viewer,j.operationId()));data.put("rebuilt",category.equals("UI_PATCH")&&uiRebuildEvidence(viewer.getUUID(),j.operationId()).isPresent());
        }else throw new IllegalArgumentException("PACKAGE_OPERATION_CATEGORY");
        var head=library.get(pkg).filter(p->ownedPackage(viewer.getUUID(),pkg,p.revision()).isPresent()).map(ServerPackageRuntime::headView).orElse(Map.of());
        return Map.of("category",category,"job",Map.copyOf(data),"head",head);
    }
    public RuntimePackage importOwned(ServerPlayer viewer,UUID id,dev.mineagent.runtime.worker.generation.ParsedRuntimePackage parsed)throws Exception{
        requireServerThread();if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.MANAGE_PACKAGES))throw new SecurityException("PACKAGE_IMPORT_DENIED");
        for(var file:parsed.files())if(!content.put(file.content()).sha256().equals(file.sha256()))throw new IllegalArgumentException("IMPORT_RESOURCE_HASH");
        var candidate=dev.mineagent.runtime.worker.generation.RuntimePackagePublisher.prepare(parsed,id,MineAgentRuntimeServices.identity(server),dev.mineagent.runtime.api.packages.PackageOrigin.EXPLICIT_IMPORT);
        var result=imports.install(viewer.getUUID(),candidate);MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"PACKAGE_EXPLICIT_IMPORT",id.toString(),candidate.canonicalSha256());return result;
    }
    public PackageUiPatchService.Submission patch(ServerPlayer viewer,UUID agent,UUID operation,UUID packageId,long revision,String prompt)throws Exception{
        requireServerThread();if(!allowed(viewer.getUUID(),agent)||!parentCurrent(operation))throw new SecurityException("PERMISSION_DENIED");
        var previous=patches.find(viewer.getUUID(),operation).orElse(null);
        var base=previous==null?ownedPackage(viewer.getUUID(),packageId,revision).orElseThrow(()->new SecurityException("PACKAGE_NOT_OWNED")):previous.base();
        if(!base.packageId().equals(packageId)||base.revision()!=revision)throw new IllegalStateException("STALE_PACKAGE");
        var submitted=patches.submit(viewer.getUUID(),agent,operation,base,prompt,true,budgetParent(operation,"PATCH"));if(submitted.duplicate())return submitted;var job=submitted.job();
        MineAgentRuntimeServices.worker(server).generateUiPatch(MineAgentRuntimeServices.config(server),job,MineAgentRuntimeServices.identity(server)).whenComplete((result,error)->server.execute(()->{
            if(closed)return;try{
                if(error!=null||result==null)patches.fail(job,"UI_PATCH_GENERATION_FAILED");
                else if(!result.errorCode().isEmpty())patches.failed(job,result.errorCode(),result.providerId(),result.rawOutputSha256());
                else patches.ready(job,result.candidate(),result.providerId(),result.rawOutputSha256(),allowed(job.ownerPlayerId(),job.agentId())&&parentCurrent(job.operationId()));
                        }catch(Exception failure){try{patches.fail(job,"UI_PATCH_COMMIT_FAILED");}catch(Exception ignored){}}
            finally{if(result!=null&&!result.rawOutputSha256().isEmpty())try{patches.recordOutputEvidence(job,result.providerId(),result.rawOutputSha256());}catch(Exception rejected){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("UI patch raw evidence not attached operation={} code={}",job.operationId(),rejected.getClass().getSimpleName());}}
        }));return submitted;
    }
    public PackageUiPatchJob patchAction(UUID owner,UUID operation,String action)throws Exception{
        requireServerThread();var job=patches.get(owner,operation);boolean authorized=allowed(owner,job.agentId());
        if(action.equals("apply")&&job.state().equals("READY"))authorized&=parentCurrent(operation);
        if(authorized&&((action.equals("apply")&&patches.candidate(owner,operation).isPresent())||(action.equals("rollback")&&job.state().equals("APPLIED")&&ownedPackage(owner,job.base().packageId(),job.headRevision()).isPresent())))
            ServerUiRuntime.get(server).packageChanged(job.base().packageId(),action.equals("apply")?job.base().revision()+1:job.headRevision()+1);
        var result=switch(action){case "apply"->patches.apply(owner,operation,authorized);case "rollback"->patches.rollback(owner,operation,authorized);case "cancel"->patches.cancel(owner,operation);default->throw new IllegalArgumentException("UI_PATCH_ACTION");};
        if((result.state().equals("APPLIED")||result.state().equals("ROLLED_BACK"))&&!result.state().equals(job.state()))ServerUiRuntime.get(server).packageChanged(result.base().packageId(),result.headRevision());
        return result;
    }
    public Optional<RuntimePackage> candidate(UUID owner,UUID operation){requireServerThread();var j=patches.get(owner,operation);return allowed(owner,j.agentId())?patches.candidate(owner,operation):Optional.empty();}
    public PackageGenerationService.Submission submit(ServerPlayer viewer, UUID agent, UUID operation, String prompt) throws Exception {
        return submit(viewer,agent,operation,prompt,"UI_PACKAGE");
    }
    public PackageGenerationService.Submission submit(ServerPlayer viewer, UUID agent, UUID operation, String prompt,String purpose) throws Exception {
        return submit(viewer,agent,operation,prompt,purpose,null,0,"",false,null);
    }
    public PackageGenerationService.Submission submit(ServerPlayer viewer,UUID agent,UUID operation,String prompt,String purpose,dev.mineagent.runtime.core.compile.NativeCoderContext nativeSelection)throws Exception{
        return submit(viewer,agent,operation,prompt,purpose,null,0,"",false,nativeSelection);
    }
    public PackageGenerationService.Submission repair(ServerPlayer viewer,UUID agent,UUID operation,UUID sourceOperation,long sourceRevision,String sourceHash,String instructions,boolean confirmed)throws Exception{
        return submit(viewer,agent,operation,instructions,"",sourceOperation,sourceRevision,sourceHash,confirmed,null);
    }
    private boolean generationCurrent(PackageGenerationJob job){
        if(closed||!allowed(job.ownerPlayerId(),job.agentId())||!parentCurrent(job.operationId())||!jobs.current(job))return false;
        if(job.nativeSelection()!=null)try{var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();return latest.hash().equals(job.nativeSelection().snapshot())&&latest.snapshot().environment().fingerprint().equals(job.nativeSelection().environment())&&dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint().equals(job.nativeSelection().environment())&&job.nativeSelection().overlays().stream().allMatch(o->o.processEpoch().equals(dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.state().processEpoch()));}catch(Exception unavailable){return false;}
        return true;
    }
    public List<Map<String,Object>> generationViews(UUID owner){requireServerThread();return jobs.list(owner).stream().limit(32).map(j->{
        var value=new LinkedHashMap<String,Object>();value.put("operationId",j.operationId());value.put("taskId",j.taskId());value.put("agentId",j.agentId());value.put("packageId",j.packageId());value.put("packageRevision",j.packageRevision());value.put("state",j.state());value.put("errorCode",j.errorCode());value.put("providerId",j.providerId());value.put("updatedAt",j.updatedAtEpochMillis());value.put("purpose",j.purpose());value.put("nativeTypeCount",j.nativeSelection()==null?0:j.nativeSelection().types().size());value.put("nativeOverlayCount",j.nativeSelection()==null?0:j.nativeSelection().overlays().size());value.put("nativeSnapshot",j.nativeSelection()==null?"":j.nativeSelection().snapshot());value.put("nativeContextHash",j.nativeContext()==null?"":j.nativeContext().sha256());value.put("nativeCompilationSnapshot",j.nativeContext()==null?"":j.nativeContext().compilationSnapshot());
        value.put("jobRevision",j.revision());value.put("rawOutputSha256",j.rawOutputSha256());value.put("repairable",j.state().equals("FAILED")&&!j.rawOutputSha256().isEmpty()&&library.get(j.packageId()).isEmpty()&&allowed(owner,j.agentId()));
        if(j.repairSource()!=null)value.put("repairOf",j.repairSource().operationId());return Map.copyOf(value);
    }).toList();}
    private PackageGenerationService.Submission submit(ServerPlayer viewer,UUID agent,UUID operation,String prompt,String purpose,UUID sourceOperation,long sourceRevision,String sourceHash,boolean confirmed,dev.mineagent.runtime.core.compile.NativeCoderContext nativeSelection)throws Exception{
        requireServerThread();
        boolean allowed = allowed(viewer.getUUID(), agent);
        if (!allowed || !parentCurrent(operation)) throw new SecurityException("PERMISSION_DENIED");
        var config = MineAgentRuntimeServices.config(server);
        var values = config.snapshot().values();
        boolean provider = (!values.getOrDefault("provider.openai.baseUrl", "").isBlank() && !values.getOrDefault("provider.openai.model", "").isBlank())
                || (!values.getOrDefault("provider.ollama.baseUrl", "").isBlank() && !values.getOrDefault("provider.ollama.model", "").isBlank());
        if (!provider) throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
        var submitted = sourceOperation==null?jobs.submit(viewer.getUUID(), agent, operation, prompt, true,purpose,budgetParent(operation,"GENERATION"),nativeSelection):jobs.submitRepair(viewer.getUUID(),agent,operation,sourceOperation,sourceRevision,sourceHash,prompt,true,confirmed);
        if (submitted.duplicate()) return submitted;
        var job = submitted.job();var ticket=new java.util.concurrent.atomic.AtomicReference<>(job);
        var permit=new java.util.concurrent.atomic.AtomicBoolean(true);generationPermits.put(job.taskId(),permit);
        java.util.function.BooleanSupplier dispatch=()->{
            if(!permit.get()||closed)return false;
            try{boolean current=server.submit(()->permit.get()&&generationCurrent(job)).get(3,java.util.concurrent.TimeUnit.SECONDS);if(!current)permit.set(false);return current;}
            catch(Exception invalid){permit.set(false);return false;}
        };
        try {
            MineAgentRuntimeServices.worker(server).generateUiPackage(config, job, MineAgentRuntimeServices.identity(server),dispatch,context->{
                    try{var bound=server.submit(()->{var current=ticket.get();if(!generationCurrent(current))throw new IllegalStateException("GENERATION_CONTEXT_CHANGED");try{return jobs.bindNativeContext(current,context);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).get(3,java.util.concurrent.TimeUnit.SECONDS);ticket.set(bound);}
                    catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
                }).whenComplete((result, failure) -> server.execute(() -> {
                        var current=ticket.get();
                        if (closed) return; // Do not recreate services or commit into a later server lifecycle.
                        try {
                            if (failure != null || result == null) jobs.fail(current, dev.mineagent.runtime.worker.generation.PackageGenerationFailure.transport(failure));
                            else if(!permit.get()||!generationCurrent(current))jobs.fail(current,"GENERATION_AUTHORITY_OR_TASK_CHANGED",result.providerId(),result.rawOutputSha256());
                            else if(!result.errorCode().isEmpty())jobs.fail(current,result.errorCode(),result.providerId(),result.rawOutputSha256());
                            else if (current.purpose().equals("UI_PACKAGE")&&uiEntry(result.runtimePackage()).isEmpty()) jobs.fail(current, "UI_ENTRYPOINT_MISSING",result.providerId(),result.rawOutputSha256());
                            else {
                                if(result.runtimePackage().activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.RESOURCE_RELOAD)ResourcePackPlan.inspect(result.runtimePackage());
                                else if(result.runtimePackage().activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.BOOT_EXTENSION)dev.mineagent.runtime.core.boot.BootExtensionPlan.validate(result.runtimePackage());
                                else if(current.purpose().equals("WORLD_CONTENT")){
                                    if(Set.of(dev.mineagent.runtime.api.packages.ActivationMode.DATA_RELOAD,dev.mineagent.runtime.api.packages.ActivationMode.WORLD_REOPEN).contains(result.runtimePackage().activationMode()))dev.mineagent.runtime.neoforge.content.ManagedDataPackGuard.validateReloadTargets(DataPackPlan.inspect(result.runtimePackage()));
                                    else{var plan=WorldContentPlan.resolve(result.runtimePackage(),content);for(var definition:plan.restoreEntrypoints().keySet())dev.mineagent.runtime.neoforge.content.WorldContentRuntime.verifyRegistration(plan,definition);}
                                }
                                jobs.complete(current, result.runtimePackage(), result.providerId(), result.rawOutputSha256(), allowed(current.ownerPlayerId(), current.agentId())&&parentCurrent(current.operationId()));
                            }
                        } catch (Exception commitFailure) {
                            try { jobs.fail(current, DataPackPlan.PLAN_ERRORS.contains(Objects.toString(commitFailure.getMessage(),""))?commitFailure.getMessage():"PACKAGE_COMMIT_FAILED",result==null?"":result.providerId(),result==null?"":result.rawOutputSha256()); } catch (Exception ignored) { /* Persisted GENERATING is interrupted on recovery. */ }
                            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Package commit failed task={} code={}", current.taskId(), commitFailure.getClass().getSimpleName());
                        } finally {
                            generationPermits.remove(current.taskId(),permit);
                            if(result!=null&&!result.rawOutputSha256().isEmpty())try{jobs.recordOutputEvidence(current,result.providerId(),result.rawOutputSha256());}catch(Exception rejected){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Generation evidence not attached operation={} code={}",current.operationId(),rejected.getClass().getSimpleName());}
                        }
                    }));
        } catch (RuntimeException dispatchFailure) { permit.set(false);generationPermits.remove(job.taskId(),permit);jobs.fail(job, "WORKER_UNAVAILABLE"); }
        return new PackageGenerationService.Submission(jobs.find(viewer.getUUID(),operation).orElseThrow(),false);
    }
    public PackageGenerationJob cancel(UUID owner, UUID operation) throws Exception { requireServerThread(); return jobs.cancel(owner, operation); }
    public Optional<RuntimePackage> ownedPackage(UUID viewer, UUID packageId, long revision) {
        requireServerThread();
        if (!jobs.ownsPublished(viewer, packageId, revision)&&!patches.ownsPublished(viewer,packageId,revision)&&!worldPatches.ownsPublished(viewer,packageId,revision)&&!imports.owns(viewer,packageId,revision)&&!copyOwned(viewer,packageId,revision)&&!studioOwned(viewer,packageId,revision)) return Optional.empty();
        return library.get(packageId);
    }
    public RuntimePackage resourceTarget(UUID viewer,UUID id,long revision,String hash){requireServerThread();var p=ownedPackage(viewer,id,revision).orElseThrow(()->new SecurityException("RESOURCE_PACK_OWNER"));if(!p.canonicalSha256().equals(hash))throw new IllegalStateException("RESOURCE_PACK_SOURCE_CHANGED");ResourcePackPlan.inspect(p);return p;}
    public java.util.concurrent.CompletableFuture<byte[]> prepareResources(UUID viewer,UUID id,long revision,String hash){
        var p=resourceTarget(viewer,id,revision,hash);if(resourcePreparing.size()>=4||!resourcePreparing.add(viewer))throw new IllegalStateException("RESOURCE_PACK_TRANSFER_BUSY");
        try{return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return ResourcePackPlan.inspect(p).bundle(content);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},io);}catch(RuntimeException failure){resourcePreparing.remove(viewer);throw failure;}
    }
    public void resourcesPrepared(UUID viewer){requireServerThread();resourcePreparing.remove(viewer);}
    public PackageTransferLeases.Offer resourceOffer(UUID viewer,UUID session,UUID id,long revision,String hash,byte[] body)throws Exception{resourceTarget(viewer,id,revision,hash);return resourceTransfers.offer(viewer,session,id,revision,body);}
    public byte[] resourceChunk(UUID viewer,UUID session,UUID transfer,int offset){requireServerThread();return resourceTransfers.chunk(viewer,session,transfer,offset,o->ownedPackage(viewer,o.packageId(),o.packageRevision()).filter(p->p.activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.RESOURCE_RELOAD).isPresent());}
    public void releaseResources(UUID viewer,UUID session,UUID transfer){requireServerThread();resourceTransfers.release(viewer,session,transfer);}
    public RuntimePackage clientScriptTarget(UUID viewer,UUID id,long revision,String hash){requireServerThread();var p=ownedPackage(viewer,id,revision).orElseThrow(()->new SecurityException("CLIENT_SCRIPT_OWNER"));if(!p.canonicalSha256().equals(hash))throw new IllegalStateException("CLIENT_SCRIPT_SOURCE_CHANGED");ClientScriptPlan.inspect(p);return p;}
    public java.util.concurrent.CompletableFuture<byte[]> prepareClientScript(UUID viewer,UUID id,long revision,String hash){var p=clientScriptTarget(viewer,id,revision,hash);if(clientScriptPreparing.size()>=4||!clientScriptPreparing.add(viewer))throw new IllegalStateException("CLIENT_SCRIPT_TRANSFER_BUSY");try{return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return ClientScriptPlan.inspect(p).bundle(content);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},io);}catch(RuntimeException failure){clientScriptPreparing.remove(viewer);throw failure;}}
    public void clientScriptPrepared(UUID viewer){requireServerThread();clientScriptPreparing.remove(viewer);}
    public PackageTransferLeases.Offer clientScriptOffer(UUID viewer,UUID session,UUID id,long revision,String hash,byte[] body)throws Exception{clientScriptTarget(viewer,id,revision,hash);return clientScriptTransfers.offer(viewer,session,id,revision,body);}
    public byte[] clientScriptChunk(UUID viewer,UUID session,UUID transfer,int offset){requireServerThread();return clientScriptTransfers.chunk(viewer,session,transfer,offset,o->ownedPackage(viewer,o.packageId(),o.packageRevision()).filter(p->{try{ClientScriptPlan.inspect(p);return true;}catch(Exception invalid){return false;}}).isPresent());}
    public void releaseClientScript(UUID viewer,UUID session,UUID transfer){requireServerThread();clientScriptTransfers.release(viewer,session,transfer);}
    public RuntimePackage clientJavaTarget(UUID viewer,UUID id,long revision,String hash){requireServerThread();var p=ownedPackage(viewer,id,revision).orElseThrow(()->new SecurityException("CLIENT_JAVA_OWNER"));if(!p.canonicalSha256().equals(hash))throw new IllegalStateException("CLIENT_JAVA_SOURCE_CHANGED");ClientJavaPlan.inspect(p);return p;}
    public java.util.concurrent.CompletableFuture<byte[]> prepareClientJava(UUID viewer,UUID id,long revision,String hash){var p=clientJavaTarget(viewer,id,revision,hash);if(clientJavaPreparing.size()>=4||!clientJavaPreparing.add(viewer))throw new IllegalStateException("CLIENT_JAVA_TRANSFER_BUSY");try{return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return ClientJavaPlan.inspect(p).bundle(content);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},io);}catch(RuntimeException failure){clientJavaPreparing.remove(viewer);throw failure;}}
    public void clientJavaPrepared(UUID viewer){requireServerThread();clientJavaPreparing.remove(viewer);}
    public PackageTransferLeases.Offer clientJavaOffer(UUID viewer,UUID session,UUID id,long revision,String hash,byte[] body)throws Exception{clientJavaTarget(viewer,id,revision,hash);return clientJavaTransfers.offer(viewer,session,id,revision,body);}
    public byte[] clientJavaChunk(UUID viewer,UUID session,UUID transfer,int offset){requireServerThread();return clientJavaTransfers.chunk(viewer,session,transfer,offset,o->ownedPackage(viewer,o.packageId(),o.packageRevision()).filter(p->{try{ClientJavaPlan.inspect(p);return true;}catch(Exception invalid){return false;}}).isPresent());}
    public void releaseClientJava(UUID viewer,UUID session,UUID transfer){requireServerThread();clientJavaTransfers.release(viewer,session,transfer);}
    public java.util.concurrent.CompletableFuture<byte[]> preparePreview(UUID viewer, UUID packageId, long revision) {
        return preparePreview(viewer,packageId,revision,null);
    }
    public java.util.concurrent.CompletableFuture<byte[]> preparePreview(UUID viewer,UUID packageId,long revision,UUID patchOperation){
        return preparePreview(viewer,packageId,revision,patchOperation,false);
    }
    public java.util.concurrent.CompletableFuture<byte[]> preparePreview(UUID viewer,UUID packageId,long revision,UUID patchOperation,boolean hud){
        return prepareEntry(viewer,packageId,revision,patchOperation,hud?"hud":"ui");
    }
    public java.util.concurrent.CompletableFuture<byte[]> prepareEntry(UUID viewer,UUID packageId,long revision,UUID patchOperation,String name){
        requireServerThread();
        if(!Set.of("ui","hud","container").contains(name)||(!name.equals("ui")&&patchOperation!=null))throw new IllegalArgumentException("NAMED_CANDIDATE_UNSUPPORTED");
        var pkg = (patchOperation==null?ownedPackage(viewer,packageId,revision):candidate(viewer,patchOperation).filter(p->p.packageId().equals(packageId)&&p.revision()==revision)).orElseThrow(() -> new SecurityException("PACKAGE_NOT_OWNED"));
        String entry=(name.equals("ui")?PackageUiEntrypoints.select(pkg.entrypoints(),false):PackageUiEntrypoints.named(pkg.entrypoints(),name)).orElseThrow(()->new IllegalStateException("UI_ENTRYPOINT_MISSING"));
        if (preparing.size() >= 4 || !preparing.add(viewer)) throw new IllegalStateException("UI_TRANSFER_BUSY");
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return PackagePreviewBundle.encode(pkg, entry, content); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(new IllegalStateException("UI_BUNDLE_INVALID")); }
            }, io);
        } catch (RuntimeException rejected) { preparing.remove(viewer); throw rejected; }
    }
    public PackageTransferLeases.Offer offer(UUID viewer, UUID session, UUID packageId, long revision, byte[] body) throws Exception {
        return offer(viewer,session,packageId,revision,body,null);
    }
    public PackageTransferLeases.Offer offer(UUID viewer,UUID session,UUID packageId,long revision,byte[] body,UUID patchOperation)throws Exception{
        requireServerThread();
        if ((patchOperation==null?ownedPackage(viewer,packageId,revision):candidate(viewer,patchOperation).filter(p->p.packageId().equals(packageId)&&p.revision()==revision)).isEmpty())throw new SecurityException("STALE_PACKAGE");
        candidateOffers.values().removeIf(o->o.expires()<=System.currentTimeMillis()||o.owner().equals(viewer));
        var offer=transfers.offer(viewer,session,packageId,revision,body);if(patchOperation!=null)candidateOffers.put(offer.transferId(),new CandidateOffer(viewer,patchOperation,offer.expiresAt()));return offer;
    }
    public void prepared(UUID viewer) { preparing.remove(viewer); }
    public byte[] chunk(UUID viewer, UUID session, UUID transfer, int offset) {
        requireServerThread();
        return transfers.chunk(viewer, session, transfer, offset, offer -> {
            var candidate=candidateOffers.get(offer.transferId());return candidate==null?ownedPackage(viewer,offer.packageId(),offer.packageRevision()).isPresent()
                    :candidate.owner().equals(viewer)&&candidate(viewer,candidate.operation()).filter(p->p.packageId().equals(offer.packageId())&&p.revision()==offer.packageRevision()).isPresent();
        });
    }
    public static synchronized void disconnect(MinecraftServer server, UUID viewer) {
        var runtime = RUNTIMES.get(server); if (runtime != null) {runtime.transfers.release(viewer);runtime.resourceTransfers.release(viewer);runtime.clientScriptTransfers.release(viewer);runtime.clientJavaTransfers.release(viewer);runtime.candidateOffers.values().removeIf(o->o.owner().equals(viewer));}
    }
    public boolean closed() { return closed; }
    public RuntimePackageLibrary worldLibrary(){requireServerThread();return library;}
    public ContentAddressedStore worldContent(){requireServerThread();return content;}
    public dev.mineagent.runtime.core.compile.NativeKnowledgeStore nativeKnowledge(){requireServerThread();return nativeKnowledge;}
    private boolean allowed(UUID owner, UUID agent) {
        var viewer = server.getPlayerList().getPlayer(owner);
        boolean operator = viewer != null && viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return MineAgentRuntimeServices.bodies(server).definitions().stream().anyMatch(a -> a.agentId().equals(agent))
                && MineAgentRuntimeServices.permissions(server).allowed(owner, operator, PermissionAction.START_TASK)
                && MineAgentRuntimeServices.permissions(server).canMutateAgent(MineAgentRuntimeServices.bodies(server).definitions().stream()
                        .filter(a->a.agentId().equals(agent)).findFirst().orElse(null), owner, operator);
    }
    public static Optional<String> uiEntry(RuntimePackage p) {
        return PackageUiEntrypoints.select(p.entrypoints(),false);
    }
    private void requireServerThread() {
        if (!server.isSameThread() || closed) throw new IllegalStateException("PACKAGE_RUNTIME_UNAVAILABLE");
    }
    @Override public void close() throws Exception {
        closed = true; io.shutdownNow(); transfers.clear();resourceTransfers.clear();resourcePreparing.clear();clientScriptTransfers.clear();clientScriptPreparing.clear();clientJavaTransfers.clear();clientJavaPreparing.clear();candidateOffers.clear(); preparing.clear();
        worldPatchPermits.values().forEach(p->p.set(false));worldPatchPermits.clear();generationPermits.values().forEach(p->p.set(false));generationPermits.clear();worldPatchTaskChanges.close();
        try{nativeKnowledge.close();}finally{try{worldPatches.close();}finally{try {imports.close();}finally{try {patches.close();}finally{try { jobs.close(); } finally { try{nativeCompatibility.close();}finally{library.close();} }}}}}
    }
}
