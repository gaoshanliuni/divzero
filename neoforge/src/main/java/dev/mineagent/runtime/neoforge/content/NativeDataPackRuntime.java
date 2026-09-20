package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit, one-at-a-time Vanilla data reload. It never starts Rhino or fabricates world instances. */
public final class NativeDataPackRuntime implements AutoCloseable {
    private static final Map<MinecraftServer,NativeDataPackRuntime> RUNTIMES=new IdentityHashMap<>();
    private static final Map<String,MinecraftServer> LIVE=new ConcurrentHashMap<>();
    public static boolean liveWorld(Path save){var server=LIVE.get(DataPackInstallStore.pathKey(save));return server!=null&&server.isRunning();}
    private final MinecraftServer server;private final ServerPackageRuntime packages;private final DataPackInstallStore store;private final Path save;private final ExecutorService files;
    private volatile boolean closed;private UUID active;private AutoCloseable permit;private Pending pending;private PendingWorld pendingWorld;private WorldFault worldFault;
    private record WorldFault(DataPackInstallStore.Job job,String code){}
    private record PendingWorld(WorldReopenPlans.Plan plan,boolean cancelled,String code){}
    private record Pending(DataPackInstallStore.Job job,boolean success,String code){}
    private NativeDataPackRuntime(MinecraftServer server)throws Exception{
        this.server=server;packages=ServerPackageRuntime.get(server);save=server.getWorldPath(LevelResource.ROOT).toRealPath();
        store=new DataPackInstallStore(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server));
        files=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mineagent-data-pack-files");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public static synchronized NativeDataPackRuntime get(MinecraftServer server){var old=RUNTIMES.get(server);if(old!=null)return old;try{var created=new NativeDataPackRuntime(server);RUNTIMES.put(server,created);LIVE.put(DataPackInstallStore.pathKey(created.save),server);created.observeReopen();return created;}catch(Exception failure){throw new IllegalStateException("DATA_PACK_STORE_UNAVAILABLE",failure);}}
    public static void tick(MinecraftServer server){var runtime=RUNTIMES.get(server);if(runtime!=null&&!runtime.closed&&server.getTickCount()%20==0){if(runtime.worldFault!=null){var p=runtime.worldFault;runtime.worldFailure(p.job(),p.code());}else if(runtime.pendingWorld!=null){var p=runtime.pendingWorld;runtime.finishWorld(p.plan(),p.cancelled(),p.code());}else if(runtime.pending!=null){var p=runtime.pending;runtime.settle(p.job(),p.success(),p.code());}}}
    public static synchronized void stop(MinecraftServer server){var runtime=RUNTIMES.remove(server);if(runtime!=null)try{runtime.close();}catch(Exception ignored){}}
    private void viewer(ServerPlayer player){if(closed||!server.isSameThread()||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(player.getUUID())!=player)throw new SecurityException("DATA_PACK_OWNER");}
    private boolean allowed(ServerPlayer player){var p=MineAgentRuntimeServices.permissions(server);boolean op=player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);return p.allowed(player.getUUID(),op,PermissionAction.RUN_CODE)&&p.allowed(player.getUUID(),op,PermissionAction.MANAGE_PACKAGES);}
    private List<String> selected(){return server.getResourceManager().listPacks().map(PackResources::packId).toList();}
    private String selection()throws Exception{return RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.of("selected",selected())));}
    private RuntimePackage owned(ServerPlayer player,UUID pkg,long revision){return packages.ownedPackage(player.getUUID(),pkg,revision).orElseThrow(()->new SecurityException("DATA_PACK_OWNER"));}
    public Map<String,Object> read(ServerPlayer player,Map<String,String> args)throws Exception{
        viewer(player);if(!args.keySet().equals(Set.of("packageId","packageRevision","offset","operationId")))throw new IllegalArgumentException("DATA_PACK_ARGUMENTS");
        UUID id=UUID.fromString(args.get("packageId"));var head=owned(player,id,Long.parseLong(args.get("packageRevision")));int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>4096)throw new IllegalArgumentException("DATA_PACK_ARGUMENTS");
        String error="";DataPackPlan plan=null;try{plan=DataPackPlan.inspect(head);ManagedDataPackGuard.validateReloadTargets(plan);String compatibility=packages.nativeCompatibility().check(head,player.getUUID());if(!compatibility.isEmpty())throw new IllegalStateException(compatibility);}catch(Exception failure){error=code(failure);}
        var waiting=store.reopen();var all=store.artifacts(player.getUUID(),id);var loaded=selected();var entries=all.stream().skip(offset).limit(8).map(a->Map.of("file",a.filename(),"canonical",a.manifest().canonicalSha256(),"state",a.state(),"loadedNow",loaded.contains("file/"+a.filename()),"zipHash",a.zipHash(),"admissionError",ManagedDataPackGuard.error(save.resolve("datapacks").resolve(a.filename())))).toList();
        var job=args.get("operationId").isBlank()?store.latest(player.getUUID(),id):store.job(player.getUUID(),UUID.fromString(args.get("operationId")));if(job.isPresent()&&!job.get().input().pkg().equals(id))throw new SecurityException("DATA_PACK_OWNER");
        boolean targetLoaded=plan!=null&&loaded.contains(plan.packId()),anyLoaded=all.stream().anyMatch(a->loaded.contains("file/"+a.filename()));
        return Map.ofEntries(Map.entry("packageId",id),Map.entry("packageRevision",head.revision()),Map.entry("canonical",head.canonicalSha256()),Map.entry("name",head.name()),Map.entry("mode",head.activationMode().name()),Map.entry("environment",packages.nativeCompatibility().environmentHash()),Map.entry("selection",selection()),Map.entry("selectedCount",loaded.size()),Map.entry("loadedNow",targetLoaded),Map.entry("artifacts",entries),Map.entry("nextOffset",offset+entries.size()),Map.entry("more",offset+entries.size()<all.size()),Map.entry("job",job.<Object>map(v->v).orElse(Map.of())),Map.entry("busy",active!=null),Map.entry("persistingResult",pending!=null||pendingWorld!=null||worldFault!=null),Map.entry("error",error),Map.entry("hookReady",ManagedDataPackGuard.ready()),Map.entry("reopenPlan",waiting.filter(v->v.input().owner().equals(player.getUUID())&&v.input().pkg().equals(id)).<Object>map(WorldReopenPlans.Plan::view).orElse(Map.of())),Map.entry("worldPlanPending",waiting.isPresent()),
                Map.entry("canEnable",error.isEmpty()&&plan!=null&&!plan.reopensWorld()&&allowed(player)&&ManagedDataPackGuard.ready()&&!targetLoaded&&active==null&&waiting.isEmpty()),
                Map.entry("canStageReopen",error.isEmpty()&&plan!=null&&plan.reopensWorld()&&allowed(player)&&ManagedDataPackGuard.ready()&&!targetLoaded&&active==null&&waiting.isEmpty()),
                Map.entry("canCancelReopen",allowed(player)&&active==null&&waiting.filter(v->v.input().owner().equals(player.getUUID())&&v.input().pkg().equals(id)&&!v.nativeStarted()&&!v.state().equals("CANCEL_REQUESTED")&&!loaded.contains("file/"+v.filename())).isPresent()),
                Map.entry("canDisable",head.activationMode()!=dev.mineagent.runtime.api.packages.ActivationMode.WORLD_REOPEN&&allowed(player)&&ManagedDataPackGuard.ready()&&anyLoaded&&active==null&&waiting.isEmpty()));
    }
    public DataPackInstallStore.Input authorize(ServerPlayer player,UUID operation,Map<String,String> args)throws Exception{
        viewer(player);if(!(args.keySet().equals(Set.of("packageId","packageRevision","canonical","action","selection","environment","confirmed"))||args.keySet().equals(Set.of("packageId","packageRevision","canonical","action","selection","environment","confirmed","reopenOperation")))||!"true".equals(args.get("confirmed")))throw new SecurityException("DATA_PACK_CONFIRM_REQUIRED");
        if(!allowed(player))throw new SecurityException("DATA_PACK_PERMISSION");if(!ManagedDataPackGuard.ready())throw new IllegalStateException("DATA_PACK_GUARD_UNAVAILABLE");
        var head=owned(player,UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")));if(!head.canonicalSha256().equals(args.get("canonical")))throw new IllegalStateException("DATA_PACK_SOURCE_CHANGED");
        if(!packages.nativeCompatibility().environmentHash().equals(args.get("environment")))throw new IllegalStateException("DATA_PACK_ENVIRONMENT_CHANGED");
        String action=args.get("action");boolean world=head.activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.WORLD_REOPEN;
        if(action.equals("ENABLE")&&world||action.equals("ENABLE_AT_REOPEN")&&!world)throw new IllegalStateException("WORLD_REOPEN_EXPLICIT_ACTION_REQUIRED");
        if(action.equals("DISABLE")&&world)throw new IllegalStateException("WORLD_REOPEN_RETIREMENT_REQUIRED");
        var config=MineAgentRuntimeServices.config(server);
        return new DataPackInstallStore.Input(operation,player.getUUID(),head.packageId(),head.canonicalSha256(),head.revision(),args.get("action"),args.get("selection"),args.get("environment"),config.permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE),config.permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES),args.getOrDefault("reopenOperation","").isEmpty()?null:UUID.fromString(args.get("reopenOperation")));
    }
    public DataPackInstallStore.Job submit(ServerPlayer player,UUID operation,Map<String,String> args)throws Exception{
        var input=authorize(player,operation,args);var old=store.job(player.getUUID(),operation);if(old.isPresent()){if(!old.get().input().equals(input))throw new IllegalStateException("DATA_PACK_OPERATION_REUSED");return old.get();}
        if(active!=null)throw new IllegalStateException("DATA_PACK_BUSY");var waiting=store.reopen();if(waiting.isPresent()&&!input.action().equals("CANCEL_REOPEN"))throw new IllegalStateException("WORLD_REOPEN_PENDING");if(!selection().equals(input.selection()))throw new IllegalStateException("DATA_PACK_SELECTION_CHANGED");
        var head=owned(player,input.pkg(),input.packageRevision());DataPackPlan plan=null;
        if(Set.of("ENABLE","ENABLE_AT_REOPEN").contains(input.action())){plan=DataPackPlan.inspect(head);ManagedDataPackGuard.validateReloadTargets(plan);plan.verifySignature(MineAgentRuntimeServices.identity(server).publicKeyEncoded());String compatibility=packages.nativeCompatibility().check(head,player.getUUID());if(!compatibility.isEmpty())throw new IllegalStateException(compatibility);if(selected().contains(plan.packId()))throw new IllegalStateException("DATA_PACK_ALREADY_LOADED");for(var dep:head.dependencies().entrySet()){var p=packages.worldLibrary().get(dep.getKey()).orElseThrow();if(!p.enabled()||!p.version().equals(dep.getValue()))throw new IllegalStateException("DATA_PACK_DEPENDENCY_CHANGED");}}
        else if(input.action().equals("CANCEL_REOPEN")){
            var r=waiting.orElseThrow(()->new IllegalStateException("WORLD_REOPEN_PLAN_MISSING"));if(r.state().equals("CANCEL_REQUESTED"))throw new IllegalStateException("WORLD_REOPEN_CANCEL_ALREADY_REQUESTED");if(input.reopenOperation()==null||!r.input().operation().equals(input.reopenOperation())||!r.input().owner().equals(player.getUUID())||!r.input().pkg().equals(input.pkg())||selected().contains("file/"+r.filename()))throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");WorldReopenBootstrap.cancellable(new WorldReopenBootstrap.PlanContext(save,r));
        }
        else if(store.artifacts(player.getUUID(),input.pkg()).stream().noneMatch(a->selected().contains("file/"+a.filename())))throw new IllegalStateException("DATA_PACK_NOT_LOADED");
        var job=store.begin(input);active=operation;
        try{
            if(plan!=null){if(plan.reopensWorld())WorldReopenVerifier.noRemoval(plan,store.artifacts(player.getUUID(),input.pkg()),selected());job=store.stage(job,head,save);}var request=job;var prepared=plan;var content=packages.worldContent();
            CompletableFuture.supplyAsync(()->{try{if(closed)throw new IllegalStateException("DATA_PACK_SERVER_STOPPING");return prepared==null?"":prepared.publish(save,content);}catch(Exception failure){throw new CompletionException(failure);}},files).whenComplete((hash,failure)->server.execute(()->{if(closed)return;if(failure!=null)settle(request,false,code(failure));else if(request.input().action().equals("CANCEL_REOPEN"))cancelWorld(player,request);else if(prepared!=null&&prepared.reopensWorld())stageWorld(player,request,prepared,hash);else dispatch(player,request,prepared,hash);}));
            return job;
        }catch(Exception failure){settle(job,false,code(failure));return store.job(player.getUUID(),operation).orElseThrow();}
    }
    private void current(ServerPlayer player,DataPackInstallStore.Input input)throws Exception{
        viewer(player);if(!allowed(player))throw new SecurityException("DATA_PACK_PERMISSION_CHANGED");var head=owned(player,input.pkg(),input.packageRevision());if(!head.canonicalSha256().equals(input.canonical()))throw new IllegalStateException("DATA_PACK_SOURCE_CHANGED");
        var config=MineAgentRuntimeServices.config(server);if(config.permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE)!=input.runGeneration()||config.permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES)!=input.manageGeneration())throw new SecurityException("DATA_PACK_PERMISSION_CHANGED");
        if(!packages.nativeCompatibility().environmentHash().equals(input.environment()))throw new IllegalStateException("DATA_PACK_ENVIRONMENT_CHANGED");
    }
    private void stageWorld(ServerPlayer player,DataPackInstallStore.Job job,DataPackPlan plan,String hash){
        WorldReopenPlans.Plan staged=null;
        try{
            current(player,job.input());if(!selection().equals(job.input().selection()))throw new IllegalStateException("DATA_PACK_SELECTION_CHANGED");
            var source=WorldReopenBootstrap.source(server.getPackRepository());var metadata=ManagedDataPackGuard.metadata(plan,save.resolve("datapacks").resolve(plan.filename()),MineAgentRuntimeServices.worldId(server),source.id());
            if(metadata==null||!metadata.getCompatibility().isCompatible()||!metadata.getRequestedFeatures().isSubsetOf(server.getWorldData().enabledFeatures()))throw new IllegalStateException("DATA_PACK_FORMAT_INCOMPATIBLE");
            var before=server.getWorldData().getDataConfiguration();if(!before.dataPacks().getEnabled().equals(selected()))throw new IllegalStateException("WORLD_REOPEN_SELECTION_UNSETTLED");
            // 26.1 world generation is separate SavedData. Flush the current real settings; never write speculative generator objects into it.
            server.getWorldGenSettings().setDirty();server.saveEverything(true,true,true);String baseline=WorldReopenBootstrap.worldgenHash(save);current(player,job.input());if(!server.getWorldData().getDataConfiguration().equals(before)||!selection().equals(job.input().selection()))throw new IllegalStateException("WORLD_REOPEN_SELECTION_UNSETTLED");
            var desired=new ArrayList<>(before.dataPacks().getEnabled());var own=store.artifacts(player.getUUID(),job.input().pkg()).stream().map(a->"file/"+a.filename()).collect(java.util.stream.Collectors.toSet());desired.removeIf(own::contains);desired.add(plan.packId());
            var disabled=new LinkedHashSet<>(before.dataPacks().getDisabled());disabled.addAll(own);disabled.removeAll(desired);
            staged=store.prepareReopen(job,hash,source.id(),before.dataPacks().getEnabled(),before.dataPacks().getDisabled(),desired,List.copyOf(disabled),baseline);
            server.getWorldData().setDataConfiguration(new net.minecraft.world.level.WorldDataConfiguration(new net.minecraft.world.level.DataPackConfig(desired,List.copyOf(disabled)),before.enabledFeatures()));
            server.saveEverything(true,true,true);verifySavedConfiguration(desired,List.copyOf(disabled));current(player,job.input());if(!WorldReopenBootstrap.same(server.getWorldData().getDataConfiguration().dataPacks(),desired,List.copyOf(disabled)))throw new IllegalStateException("WORLD_REOPEN_SELECTION_CONFLICT");if(!selection().equals(job.input().selection()))throw new IllegalStateException("WORLD_REOPEN_RUNNING_SELECTION_CHANGED");
            var head=owned(player,job.input().pkg(),job.input().packageRevision());if(!head.enabled()&&!packages.worldLibrary().setEnabled(head.packageId(),head.revision(),true).accepted())throw new IllegalStateException("DATA_PACK_LIBRARY_COMMIT_FAILED");
            store.readyReopen(staged);active=null;
        }catch(Exception failure){worldFailure(job,code(failure));}
    }
    private void cancelWorld(ServerPlayer player,DataPackInstallStore.Job job){
        try{
            current(player,job.input());var plan=store.reopen().orElseThrow();if(!plan.input().operation().equals(job.input().reopenOperation())||selected().contains("file/"+plan.filename()))throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");
            WorldReopenBootstrap.cancellable(new WorldReopenBootstrap.PlanContext(save,plan));var current=server.getWorldData().getDataConfiguration();
            if(!WorldReopenBootstrap.same(current.dataPacks(),plan.desiredEnabled(),plan.desiredDisabled())&&!WorldReopenBootstrap.same(current.dataPacks(),plan.beforeEnabled(),plan.beforeDisabled()))throw new IllegalStateException("WORLD_REOPEN_SELECTION_CONFLICT");
            store.cancelReopen(plan,job.input().operation(),"PLAYER:"+player.getUUID());
            server.getWorldData().setDataConfiguration(new net.minecraft.world.level.WorldDataConfiguration(new net.minecraft.world.level.DataPackConfig(plan.beforeEnabled(),plan.beforeDisabled()),current.enabledFeatures()));server.saveEverything(true,true,true);verifySavedConfiguration(plan.beforeEnabled(),plan.beforeDisabled());finishWorld(plan,true,"WORLD_REOPEN_NEXT_SELECTION_RESTORED");
        }catch(Exception failure){worldFailure(job,code(failure));}
    }
    /** Resolve only persistence uncertainty; never rerun save/reload/selection side effects after an ambiguous response. */
    private void worldFailure(DataPackInstallStore.Job job,String code){
        try{var plan=store.reopen();
            if(plan.isPresent()&&(plan.get().input().operation().equals(job.input().operation())||plan.get().input().operation().equals(job.input().reopenOperation()))){
                if(!Set.of("WAIT_REOPEN","CANCEL_REQUESTED").contains(plan.get().state()))store.failedReopen(plan.get(),code);
                // WAIT_REOPEN or CANCEL_REQUESTED proves the corresponding transaction committed; do not overwrite it with a late failure.
            }else store.finish(job,false,code);
            worldFault=null;active=null;
        }catch(Exception unavailable){worldFault=new WorldFault(job,code);active=job.input().operation();}
    }
    private void observeReopen(){
        WorldReopenPlans.Plan plan=null;
        try{
            var found=store.reopen();if(found.isEmpty())return;plan=found.get();var actual=selected();
            if(plan.state().equals("CANCEL_REQUESTED")){
                if(actual.contains("file/"+plan.filename())||!actual.equals(plan.beforeEnabled()))return;server.saveEverything(true,true,true);verifySavedSelection(actual);finishWorld(plan,true,"WORLD_REOPEN_CANCEL_BOOT_SAVED");return;
            }
            if(!plan.state().equals("WAIT_REOPEN")||!actual.contains("file/"+plan.filename()))return;
            var source=WorldReopenBootstrap.source(server.getPackRepository());var resource=server.getResourceManager().listPacks().filter(p->p.packId().equals("file/"+found.get().filename())).findFirst().orElseThrow();
            var proof=ManagedDataPackGuard.proof(resource).orElseThrow(()->new IllegalStateException("WORLD_REOPEN_BOOT_PROOF_MISSING"));
            if(!proof.bootstrapOpen()||!proof.worldPack()||!proof.world().equals(plan.world())||!proof.source().equals(source.id())||proof.source().equals(plan.blockedSource())||!proof.hash().equals(plan.zipHash()))throw new IllegalStateException("WORLD_REOPEN_BOOT_PROOF_CHANGED");
            var artifact=store.artifact(plan.filename());var data=DataPackPlan.inspect(artifact.manifest());verifyResources(data);
            String result="WORLD_REOPEN_VERIFIED";
            try{if(!actual.equals(plan.desiredEnabled()))throw new IllegalStateException("WORLD_REOPEN_SELECTION_CHANGED");WorldReopenVerifier.verify(server,data,data.snapshot(save.resolve("datapacks").resolve(plan.filename())));}catch(Exception unverified){result=code(unverified);}
            try{server.saveEverything(true,true,true);verifySavedSelection(actual);}catch(Exception failure){result="WORLD_REOPEN_SAVE_UNVERIFIED";}
            finishWorld(plan,false,result);
        }catch(Exception failure){if(plan!=null)try{store.failedReopen(plan,code(failure));}catch(Exception ignored){}}
    }
    private void finishWorld(WorldReopenPlans.Plan plan,boolean cancelled,String code){try{store.completeReopen(plan,cancelled,code);pendingWorld=null;active=null;}catch(Exception failure){pendingWorld=new PendingWorld(plan,cancelled,code);active=plan.input().operation();}}
    private void dispatch(ServerPlayer player,DataPackInstallStore.Job job,DataPackPlan plan,String hash){
        var request=job;
        try{
            current(player,job.input());if(!selection().equals(job.input().selection()))throw new IllegalStateException("DATA_PACK_SELECTION_CHANGED");
            var desired=new ArrayList<>(selected());var own=store.artifacts(player.getUUID(),job.input().pkg()).stream().map(a->"file/"+a.filename()).collect(java.util.stream.Collectors.toSet());desired.removeIf(own::contains);
            request=store.dispatch(job,hash);
            if(plan!=null){permit=ManagedDataPackGuard.permit(save.resolve("datapacks").resolve(plan.filename()),job.input().operation());desired.add(plan.packId());}
            server.getPackRepository().reload();
            if(plan!=null){var pack=server.getPackRepository().getPack(plan.packId());if(pack==null)throw new IllegalStateException("DATA_PACK_NOT_DISCOVERED");if(!pack.getCompatibility().isCompatible()||!pack.getRequestedFeatures().isSubsetOf(server.getWorldData().enabledFeatures()))throw new IllegalStateException("DATA_PACK_FORMAT_INCOMPATIBLE");}
            for(String id:desired)if(!server.getPackRepository().isAvailable(id))throw new IllegalStateException("DATA_PACK_OTHER_SOURCE_UNAVAILABLE");
            var ticket=request;
            // Vanilla uses managedBlock on its server thread. The whole reload is explicitly authorized; it cannot be undone by a UI timeout.
            server.reloadResources(List.copyOf(desired)).whenComplete((ignored,failure)->server.execute(()->{
                if(closed){releasePermit();return;}
                if(failure!=null){settle(ticket,false,"DATA_PACK_RELOAD_UNKNOWN");return;}
                try{current(player,ticket.input());if(!selected().equals(desired))throw new IllegalStateException("DATA_PACK_RESULT_CHANGED");
                    if(plan!=null)verifyResources(plan);
                    server.saveEverything(true,false,true);verifySavedSelection(desired);
                    if(plan!=null){var head=owned(player,ticket.input().pkg(),ticket.input().packageRevision());if(!head.enabled()){var result=packages.worldLibrary().setEnabled(head.packageId(),head.revision(),true);if(!result.accepted())throw new IllegalStateException("DATA_PACK_LIBRARY_COMMIT_FAILED");}}
                    settle(ticket,true,"DATA_PACK_RELOADED_AND_SAVE_OBSERVED");
                }catch(Exception changed){settle(ticket,false,code(changed));}
            }));
        }catch(Exception failure){settle(request,false,code(failure));}
    }
    private void verifyResources(DataPackPlan plan)throws Exception{
        for(var e:plan.files().entrySet())if(e.getKey().startsWith("data/")){
            int split=e.getKey().indexOf('/',5);var id=net.minecraft.resources.Identifier.fromNamespaceAndPath(e.getKey().substring(5,split),e.getKey().substring(split+1));
            var resource=server.getResourceManager().getResource(id).orElseThrow(()->new IllegalStateException("DATA_PACK_RESOURCE_NOT_SELECTED"));
            if(!resource.sourcePackId().equals(plan.packId()))throw new IllegalStateException("DATA_PACK_RESOURCE_OVERRIDDEN");
            byte[] data;try(var in=resource.open()){data=in.readNBytes(4*1024*1024+1);}if(data.length!=e.getValue().size()||!RuntimePackageCanonicalizer.sha256(data).equals(e.getValue().sha256()))throw new IllegalStateException("DATA_PACK_RESOURCE_HASH");
        }
    }
    private void verifySavedConfiguration(List<String> enabled,List<String> disabled)throws Exception{var actual=savedConfiguration();if(!WorldReopenBootstrap.same(actual,enabled,disabled))throw new IllegalStateException("WORLD_REOPEN_SAVE_UNVERIFIED");}
    private void verifySavedSelection(List<String> expected)throws Exception{if(!savedConfiguration().getEnabled().equals(expected))throw new IllegalStateException("DATA_PACK_SAVE_UNVERIFIED");}
    private net.minecraft.world.level.DataPackConfig savedConfiguration()throws Exception{
        Path file=save.resolve("level.dat");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("DATA_PACK_SAVE_UNVERIFIED");
        var root=net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));var data=root.getCompoundOrEmpty("Data").getCompoundOrEmpty("DataPacks");
        return net.minecraft.world.level.DataPackConfig.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,data).getOrThrow();
    }
    private void settle(DataPackInstallStore.Job job,boolean success,String code){
        if(closed){releasePermit();return;}try{store.finish(job,success,code);pending=null;active=null;}catch(Exception failure){pending=new Pending(job,success,code);}finally{releasePermit();}
    }
    private void releasePermit(){if(permit!=null){try{permit.close();}catch(Exception ignored){}permit=null;}}
    public static String code(Throwable failure){while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();String code=Objects.toString(failure.getMessage(),"");return code.matches("(?:WORLD_REOPEN|DATA_PACK|NATIVE_COMPATIBILITY|NATIVE_ENVIRONMENT)_[A-Z0-9_]{1,64}")?code:"DATA_PACK_OPERATION_FAILED";}
    @Override public void close()throws Exception{closed=true;LIVE.remove(DataPackInstallStore.pathKey(save),server);try{WorldReopenBootstrap.forget(WorldReopenBootstrap.source(server.getPackRepository()).id());}catch(Exception ignored){}releasePermit();files.shutdownNow();store.close();}
}
