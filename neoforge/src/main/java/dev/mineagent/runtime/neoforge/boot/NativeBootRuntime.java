package dev.mineagent.runtime.neoforge.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real Mod-directory installation, independently approved by a current human operator. Never a hot-load operation. */
public final class NativeBootRuntime implements AutoCloseable {
    private static final Map<MinecraftServer,NativeBootRuntime> OPEN=new IdentityHashMap<>();
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MinecraftServer server;private final BootInstallStore store;private final NativeBootUpgrades upgrades;private final NativeBootDependencies dependencies;private final Path mods,cache,receipts;
    private final ExecutorService io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"mineagent-boot-files");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;private UUID active;
    private NativeBootRuntime(MinecraftServer server)throws Exception {
        this.server=server;mods=BootFiles.directory(net.neoforged.fml.loading.FMLPaths.MODSDIR.get());
        Path root=net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("mineagent-runtime-data/boot-install");store=new BootInstallStore(root);cache=store.root().resolve("cache");receipts=store.root().resolve("receipts");upgrades=new NativeBootUpgrades(store,mods,cache);dependencies=new NativeBootDependencies(store,mods);
    }
    public static synchronized NativeBootRuntime get(MinecraftServer server){var value=OPEN.get(server);if(value==null){try{value=new NativeBootRuntime(server);OPEN.put(server,value);}catch(Exception e){throw new IllegalStateException("BOOT_STORE_UNAVAILABLE",e);}}return value;}
    public static synchronized void stop(MinecraftServer server){var value=OPEN.remove(server);if(value!=null)try{value.close();}catch(Exception ignored){}}
    public static void authorize(ServerPlayer player){var server=player.level().getServer();if(!server.isSameThread()||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(player.getUUID())!=player||!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("BOOT_HUMAN_OPERATOR_REQUIRED");var permissions=MineAgentRuntimeServices.permissions(server);for(var action:List.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES))if(!permissions.allowed(player.getUUID(),true,action))throw new SecurityException("BOOT_PERMISSION");}
    private RuntimePackage owned(ServerPlayer player,UUID id,long revision,String hash){var p=ServerPackageRuntime.get(server).ownedPackage(player.getUUID(),id,revision).orElseThrow(()->new SecurityException("BOOT_PACKAGE_OWNER"));if(!p.canonicalSha256().equals(hash))throw new IllegalStateException("BOOT_PACKAGE_CHANGED");return p;}
    public void current(ServerPlayer viewer,Map<String,String> args){
        if(closed)throw new IllegalStateException("BOOT_RUNTIME_CLOSED");authorize(viewer);
        if("build".equals(args.get("kind")))owned(viewer,UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),args.get("canonical"));
        else if("stageUpgrade".equals(args.get("kind"))||"change".equals(args.get("kind"))&&"INSTALL".equals(args.get("action"))){var b=store.get(UUID.fromString(args.get("buildId")));if(!b.owner().equals(viewer.getUUID()))throw new SecurityException("BOOT_BUILD_OWNER");owned(viewer,b.manifest().packageId(),b.manifest().revision(),b.manifest().canonicalSha256());if(!dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.state().status().equals("READY")||!dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest().hash().equals(b.nativeClasspath()))throw new IllegalStateException("BOOT_CLASSPATH_REOBSERVE_REQUIRED");}
    }
    public CompletableFuture<Map<String,Object>> read(ServerPlayer viewer,Map<String,String> args,BooleanSupplier permit) {
        authorize(viewer);UUID owner=viewer.getUUID();String env=NativePackageCompatibility.observe().fingerprint();boolean busy=active!=null;
        if(!args.keySet().equals(Set.of("kind","offset","buildId")))throw new IllegalArgumentException("BOOT_ARGUMENTS");int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>1000000)throw new IllegalArgumentException("BOOT_OFFSET");String kind=args.get("kind");
        return CompletableFuture.supplyAsync(()->{try{
            if(closed||!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
            if(kind.equals("plans")){if(!args.get("buildId").isEmpty())throw new IllegalArgumentException("BOOT_ARGUMENTS");upgrades.reconcile(owner);var rows=upgrades.page(owner,offset);return Map.<String,Object>of("plans",rows,"nextOffset",offset+rows.size(),"more",offset+rows.size()<upgrades.count(owner));}
            if(kind.equals("dependencies")){
                var b=store.get(UUID.fromString(args.get("buildId")));if(!b.owner().equals(owner))throw new SecurityException("BOOT_BUILD_OWNER");
                BootDependencyGraph bound=null,current=null;String error="";
                if(!b.artifact().isEmpty()){var artifact=BootArtifact.inspect(BootFiles.archive(cache.resolve(b.artifact()+".jar"),b.artifact()));bound=artifact.metadata().dependencies()==null?BootDependencyGraph.empty():artifact.metadata().dependencies();}
                try{current=dependencies.resolve(b.manifest(),permit);dependencies.requireCurrent(b.manifest(),current,permit);}catch(Exception failure){error=code(failure);}
                var graph=bound!=null?bound:current!=null?current:BootDependencyGraph.empty();if(offset>graph.nodes().size())throw new IllegalArgumentException("BOOT_OFFSET");var nodes=graph.nodes().stream().skip(offset).limit(8).map(n->Map.of("packageId",n.packageId(),"version",n.version(),"modId",n.modId(),"canonical",n.canonical(),"artifact",n.artifact(),"filename",n.filename())).toList();
                if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
                return Map.<String,Object>of("declared",b.manifest().dependencies(),"mode",bound==null?"CURRENT_INSTALLED_GRAPH":"BOUND_BUILD_GRAPH","nodes",nodes,"error",error,"currentMatches",current!=null&&new HashSet<>(current.nodes()).equals(new HashSet<>(graph.nodes())),"nextOffset",offset+nodes.size(),"more",offset+nodes.size()<graph.nodes().size());
            }
            if(kind.equals("diagnostics")){var b=store.get(UUID.fromString(args.get("buildId")));if(!b.owner().equals(owner))throw new SecurityException("BOOT_BUILD_OWNER");String full=String.join("\n",b.diagnostics());int length=full.codePointCount(0,full.length());if(offset>length)throw new IllegalArgumentException("BOOT_OFFSET");int end=Math.min(length,offset+4096);return Map.<String,Object>of("text",full.substring(full.offsetByCodePoints(0,offset),full.offsetByCodePoints(0,end)),"nextOffset",end,"more",end<length);}
            if(!kind.equals("list")||!args.get("buildId").isEmpty())throw new IllegalArgumentException("BOOT_ARGUMENTS");
            upgrades.reconcile(owner);var all=store.all().reversed().stream().filter(b->b.owner().equals(owner)).toList();if(offset>all.size())throw new IllegalArgumentException("BOOT_OFFSET");var page=new ArrayList<Map<String,Object>>();
            for(var item:all.stream().skip(offset).limit(8).toList()){var b=store.get(item.id());String fileState="NOT_BUILT",loaded="NOT_LOADED";
                if(!b.artifact().isEmpty()){
                    Path target=mods.resolve(BootFiles.filename(b));fileState=Files.exists(target,LinkOption.NOFOLLOW_LINKS)?"PRESENT_UNVERIFIED":"ABSENT";
                    if(fileState.equals("PRESENT_UNVERIFIED")){try{BootFiles.archive(target,b.artifact());fileState="HASH_MATCHED";}catch(Exception invalid){fileState="FILE_CHANGED";}}
                    else{Path disabled=target.resolveSibling(target.getFileName()+".disabled");if(Files.exists(disabled,LinkOption.NOFOLLOW_LINKS)){try{BootFiles.archive(disabled,b.artifact());b=store.observedOfflineRemoval(b.id());fileState="OFFLINE_DISABLED_HASH_MATCHED";}catch(Exception invalid){fileState="OFFLINE_DISABLED_CHANGED";}}}
                    var proof=NativeBootProof.get(b.modId());var info=net.neoforged.fml.ModList.get().getModFileById(b.modId());
                    if(info!=null){loaded="LOADER_PRESENT_UNVERIFIED";if(proof.isPresent()&&proof.get().packageId().equals(b.manifest().packageId())&&proof.get().canonical().equals(b.manifest().canonicalSha256())&&proof.get().archiveHash().equals(b.artifact())&&proof.get().path().equals(mods.resolve(BootFiles.filename(b)).toString()))loaded="LOADER_CONSTRUCTOR_RETURNED";}
                }
                page.add(Map.ofEntries(Map.entry("id",b.id()),Map.entry("packageId",b.manifest().packageId()),Map.entry("canonical",b.manifest().canonicalSha256()),Map.entry("packageRevision",b.manifest().revision()),Map.entry("name",b.manifest().name()),Map.entry("version",b.manifest().version()),Map.entry("revision",b.revision()),Map.entry("phase",b.phase()),Map.entry("error",b.error()),Map.entry("modId",b.modId()),Map.entry("artifact",b.artifact()),Map.entry("nativeClasspath",b.nativeClasspath()),Map.entry("environment",b.environment()),Map.entry("fileState",fileState),Map.entry("loader",loaded),Map.entry("diagnostics",b.diagnostics().size()),Map.entry("dependencyCount",b.manifest().dependencies().size()),Map.entry("replaces",b.replaces()==null?"":b.replaces().toString()),Map.entry("filename",b.artifact().isEmpty()?"":BootFiles.filename(b))));
            }
            if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");return Map.<String,Object>of("builds",page,"environment",env,"modsDirectory",mods.toString(),"gameDirectory",net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString(),"busy",busy,"nextOffset",offset+page.size(),"more",offset+page.size()<all.size());
        }catch(Exception failure){throw new CompletionException(failure);}},io);
    }
    public Map<String,Object> build(ServerPlayer viewer,UUID operation,Map<String,String> args,BooleanSupplier session)throws Exception {
        authorize(viewer);if(!args.keySet().equals(Set.of("kind","packageId","packageRevision","canonical","environment","replacementBuildId"))&&!args.keySet().equals(Set.of("kind","packageId","packageRevision","canonical","environment")))throw new IllegalArgumentException("BOOT_ARGUMENTS");
        var p=owned(viewer,UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),args.get("canonical"));BootExtensionPlan.validate(p);
        String environment=NativePackageCompatibility.observe().fingerprint();if(!environment.equals(args.get("environment")))throw new IllegalStateException("BOOT_ENVIRONMENT_CHANGED");
        byte[] key=MineAgentRuntimeServices.identity(server).publicKeyEncoded();
        var previous=args.getOrDefault("replacementBuildId","").isEmpty()?null:store.get(UUID.fromString(args.get("replacementBuildId")));
        if(previous!=null&&(!previous.owner().equals(viewer.getUUID())||!previous.manifest().packageId().equals(p.packageId())||!previous.directory().equals(mods.toString())||!Set.of("INSTALLED_PENDING_RESTART","FILE_STATE_UNKNOWN").contains(previous.phase())))throw new IllegalStateException("BOOT_REPLACEMENT_SOURCE");
        var begun=store.begin(operation,MineAgentRuntimeServices.worldId(server),viewer.getUUID(),p,Base64.getEncoder().encodeToString(key),environment,mods.toString(),previous==null?null:previous.id());if(!begun.created())return Map.of("operation",operation,"phase",begun.value().phase());
        if(active!=null){store.failed(operation,"BOOT_BUSY",List.of());throw new IllegalStateException("BOOT_BUSY");}active=operation;
        BooleanSupplier permit=()->!closed&&session.getAsBoolean();
        var worker=MineAgentRuntimeServices.worker(server);
        try{worker.compileBoot(p,key,permit,previous,()->dependencies.resolve(p,permit)).whenComplete((result,failure)->{
            try{io.execute(()->{try{
                if(failure!=null)throw new CompletionException(failure);if(result==null)throw new IllegalStateException("BOOT_BUILD_FAILED");
                var built=JSON.convertValue(result.payload(),dev.mineagent.runtime.worker.compile.BootExtensionCompiler.Result.class);
                if(!built.success()){store.failed(operation,"BOOT_COMPILE_REJECTED",built.diagnostics());return;}
                if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
                byte[] bytes=BootFiles.archive(worker.contentPath(built.artifact()),built.artifact());var artifact=BootArtifact.inspect(bytes);artifact.verify(key,p.canonicalSha256(),built.nativeClasspath(),environment);
                if(previous!=null&&(artifact.metadata().replacement()==null||!artifact.metadata().replacement().build().equals(previous.id())||!artifact.metadata().replacement().artifact().equals(previous.artifact())))throw new IllegalStateException("BOOT_REPLACEMENT_SOURCE");
                dependencies.requireCurrent(p,artifact.metadata().dependencies(),permit);
                if(!artifact.metadata().modId().equals(built.modId()))throw new IllegalStateException("BOOT_BUILD_CONTEXT_CHANGED");
                store.built(operation,built.nativeClasspath(),built.artifact(),built.modId(),built.diagnostics(),bytes.length);
                BootFiles.publish(cache,built.artifact()+".jar",bytes,built.artifact(),permit);
                // CACHING reserves retained disk budget before publishing, but never authorizes Loader installation.
                var ready=store.cached(operation);writeRecovery(ready);
            }catch(Exception e){try{store.failed(operation,code(e),List.of());}catch(Exception ignored){}}finally{server.execute(()->{if(operation.equals(active))active=null;});}});}catch(RejectedExecutionException stopped){server.execute(()->{if(operation.equals(active))active=null;});}
        });}catch(RuntimeException dispatch){active=null;store.failed(operation,"BOOT_DISPATCH_FAILED",List.of());throw dispatch;}
        return Map.of("operation",operation,"phase","BUILDING");
    }
    public Map<String,Object> change(ServerPlayer viewer,UUID operation,Map<String,String> args,BooleanSupplier session)throws Exception {
        authorize(viewer);if(!args.keySet().equals(Set.of("kind","buildId","expectedRevision","action","environment","confirmed","confirmModId"))||!"true".equals(args.get("confirmed")))throw new SecurityException("BOOT_GLOBAL_CONFIRM_REQUIRED");
        var b=store.get(UUID.fromString(args.get("buildId")));if(!b.owner().equals(viewer.getUUID())||!b.modId().equals(args.get("confirmModId"))||b.modId().isEmpty())throw new SecurityException("BOOT_BUILD_OWNER");String action=args.get("action");
        String environment=NativePackageCompatibility.observe().fingerprint();if(!environment.equals(args.get("environment"))||!b.directory().equals(mods.toString()))throw new IllegalStateException("BOOT_ENVIRONMENT_CHANGED");
        if(action.equals("INSTALL")){owned(viewer,b.manifest().packageId(),b.manifest().revision(),b.manifest().canonicalSha256());if(!b.environment().equals(environment))throw new IllegalStateException("BOOT_REBUILD_REQUIRED");if(!dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.state().status().equals("READY")||!dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest().hash().equals(b.nativeClasspath()))throw new IllegalStateException("BOOT_CLASSPATH_REOBSERVE_REQUIRED");if(net.neoforged.fml.ModList.get().getModFileById(b.modId())!=null)throw new IllegalStateException("BOOT_MOD_ID_ALREADY_LOADED");}
        var input=new BootInstallStore.Change(operation,b.id(),MineAgentRuntimeServices.worldId(server),viewer.getUUID(),action,Long.parseLong(args.get("expectedRevision")),"PREPARED","");
        if(active!=null)throw new IllegalStateException("BOOT_BUSY");if(!store.beginChange(input))return Map.of("operation",operation,"phase",store.change(operation).orElseThrow().phase());active=operation;
        BooleanSupplier permit=()->!closed&&session.getAsBoolean();
        try{io.execute(()->{boolean done=false;String error="";try{
            if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
            if(action.equals("INSTALL")){
                byte[] bytes=BootFiles.archive(cache.resolve(b.artifact()+".jar"),b.artifact());var artifact=BootArtifact.inspect(bytes);artifact.verify(Base64.getDecoder().decode(b.publicKey()),b.manifest().canonicalSha256(),b.nativeClasspath(),b.environment());
                dependencies.requireCurrent(b.manifest(),artifact.metadata().dependencies(),permit);upgrades.noOtherConflict(BootFiles.filename(b),artifact);
                writeRecovery(b);BootFiles.publish(mods,BootFiles.filename(b),bytes,b.artifact(),permit);
            }else{dependencies.requireNotUsed(b.manifest().packageId(),"");BootFiles.remove(b,mods,cache,permit);}
            done=true;
        }catch(Exception e){error=code(e);}finally{try{store.finishChange(operation,done,error);}catch(Exception ignored){}server.execute(()->{if(operation.equals(active))active=null;});}});}catch(RejectedExecutionException failure){store.finishChange(operation,false,"BOOT_DISPATCH_FAILED");active=null;throw failure;}
        return Map.of("operation",operation,"phase",action.equals("INSTALL")?"INSTALLING":"REMOVING");
    }
    public Map<String,Object> stageUpgrade(ServerPlayer viewer,UUID operation,Map<String,String> args,BooleanSupplier session)throws Exception {
        current(viewer,args);if(!args.keySet().equals(Set.of("kind","buildId","expectedRevision","environment","confirmed","confirmModId"))||!"true".equals(args.get("confirmed")))throw new SecurityException("BOOT_GLOBAL_CONFIRM_REQUIRED");
        var b=store.get(UUID.fromString(args.get("buildId")));if(b.replaces()==null||!b.owner().equals(viewer.getUUID())||!b.modId().equals(args.get("confirmModId")))throw new IllegalStateException("BOOT_REPLACEMENT_SOURCE");
        var a=store.get(b.replaces());if(!NativePackageCompatibility.observe().fingerprint().equals(args.get("environment"))||!b.environment().equals(args.get("environment")))throw new IllegalStateException("BOOT_ENVIRONMENT_CHANGED");
        var prior=store.upgrade(operation);if(prior.isPresent()){if(!prior.get().input().owner().equals(viewer.getUUID())||!prior.get().input().nextBuild().equals(b.id()))throw new IllegalStateException("BOOT_OPERATION_REUSED");return Map.of("operation",operation,"phase",prior.get().phase());}
        if(active!=null)throw new IllegalStateException("BOOT_BUSY");
        var plan=new BootUpgradePlan(1,operation,MineAgentRuntimeServices.worldId(server),viewer.getUUID(),b.manifest().packageId(),a.id(),b.id(),mods.toString(),BootFiles.filename(a),a.modId(),a.artifact(),b.artifact(),a.manifest().canonicalSha256(),b.manifest().canonicalSha256(),a.phase(),b.phase(),System.currentTimeMillis(),true);
        var staged=store.stageUpgrade(plan,a.revision(),Long.parseLong(args.get("expectedRevision")));active=operation;
        try{io.execute(()->{try{upgrades.publish(staged,()->!closed&&session.getAsBoolean());}catch(Exception e){try{store.upgradeStatus(operation,"PLAN_WRITE_FAILED",code(e));}catch(Exception ignored){}}finally{server.execute(()->{if(operation.equals(active))active=null;});}});}catch(RejectedExecutionException e){active=null;store.upgradeStatus(operation,"PLAN_WRITE_FAILED","BOOT_DISPATCH_FAILED");throw e;}
        return Map.of("operation",operation,"phase","PREPARING","planHash",plan.sha256());
    }
    public Map<String,Object> cancelUpgrade(ServerPlayer viewer,UUID operation,Map<String,String> args,BooleanSupplier session)throws Exception {
        authorize(viewer);if(!args.keySet().equals(Set.of("kind","upgradeId","planHash","confirmed"))||!"true".equals(args.get("confirmed")))throw new SecurityException("BOOT_GLOBAL_CONFIRM_REQUIRED");
        var u=store.upgrade(UUID.fromString(args.get("upgradeId"))).orElseThrow(()->new IllegalStateException("BOOT_UPGRADE_MISSING"));if(!u.input().owner().equals(viewer.getUUID())||!u.input().sha256().equals(args.get("planHash")))throw new SecurityException("BOOT_UPGRADE_OWNER");if(active!=null)throw new IllegalStateException("BOOT_BUSY");active=operation;
        try{io.execute(()->{try{upgrades.cancel(u,()->!closed&&session.getAsBoolean());}catch(Exception e){try{store.upgradeStatus(u.input().operation(),"CANCEL_FAILED",code(e));}catch(Exception ignored){}}finally{server.execute(()->{if(operation.equals(active))active=null;});}});}catch(RejectedExecutionException e){active=null;throw e;}return Map.of("operation",operation,"phase","CANCEL_REQUESTED");
    }
    private void writeRecovery(BootInstallStore.Build b)throws Exception {Path dir=BootFiles.directory(receipts),target=dir.resolve(b.id()+".json");byte[] bytes=JSON.writeValueAsBytes(b);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;Path temporary=Files.createTempFile(dir,".receipt-",".pending");try{Files.write(temporary,bytes);try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.createLink(target,temporary);}finally{Files.deleteIfExists(temporary);}}
    public static String code(Throwable failure){for(int depth=0;failure!=null&&depth<16;depth++,failure=failure.getCause()){String code=Objects.toString(failure.getMessage(),"");if(code.matches("(?:BOOT|NATIVE_CLASSPATH)_[A-Z_]{1,80}"))return code;}return "BOOT_OPERATION_FAILED";}
    @Override public void close(){closed=true;io.shutdown();var closer=new Thread(()->{try{while(!io.awaitTermination(30,TimeUnit.SECONDS)){}store.close();}catch(Exception ignored){}},"mineagent-boot-close");closer.setDaemon(true);closer.start();}
}
