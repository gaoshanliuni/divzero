package dev.mineagent.runtime.neoforge.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.persistence.WorldSaveIdentity;
import dev.mineagent.runtime.neoforge.mixin.ManagedPackRepositoryAccess;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.sql.*;
import java.util.*;

/** Bootstrap selection handling; no worker, model or world-service initialization while the world is loading. */
public final class WorldReopenBootstrap {
    public static final String CANCEL_PROPERTY="mineagent.cancelWorldReopen";
    public record Source(Path save,UUID id){}
    private record Expected(String save,Set<String> ids){}
    private static final Map<UUID,Expected> EXPECTED=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> MANAGED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ObjectMapper JSON=new ObjectMapper();
    private WorldReopenBootstrap(){}
    public static Path home(){return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("mineagent-runtime-data").toAbsolutePath().normalize();}
    public static Source source(PackRepository repository)throws Exception{var value=sourceOrNull(repository);if(value==null)throw new IllegalStateException("WORLD_REOPEN_SOURCE_AMBIGUOUS");return value;}
    private static Source sourceOrNull(PackRepository repository)throws Exception{
        var found=new ArrayList<Source>();for(var value:((ManagedPackRepositoryAccess)repository).mineagent$sources())if(value instanceof ManagedPackSourceIdentity s&&s.mineagent$packType()==PackType.SERVER_DATA&&s.mineagent$folder().getFileName().toString().equals("datapacks"))found.add(new Source(s.mineagent$folder().getParent().toRealPath(),s.mineagent$sourceId()));
        if(found.size()>1)throw new IllegalStateException("WORLD_REOPEN_SOURCE_AMBIGUOUS");return found.isEmpty()?null:found.getFirst();
    }
    public static boolean expected(UUID source,String id){var value=EXPECTED.get(source);return value!=null&&value.ids().contains(id);}
    public static boolean bootstrapOpen(UUID source,Path save,String file){var value=EXPECTED.get(source);return value!=null&&value.save().equals(DataPackInstallStore.pathKey(save))&&value.ids().contains("file/"+file);}
    public static void forget(UUID source){EXPECTED.remove(source);}
    public static WorldDataConfiguration configure(PackRepository repository,WorldDataConfiguration initial,boolean safeMode){
        try{
            boolean containsWorld=initial.dataPacks().getEnabled().stream().anyMatch(s->s.startsWith("file/mineagent-world-"));Source source;try{source=sourceOrNull(repository);}catch(Exception invalid){if(!containsWorld)return initial;throw invalid;}
            if(source==null){if(containsWorld)throw new IllegalStateException("WORLD_REOPEN_SOURCE_AMBIGUOUS");return initial;}if(containsWorld&&MANAGED.size()<512)MANAGED.add(DataPackInstallStore.pathKey(source.save()));
            var config=initial;Path dbPath=home().resolve("runtime.db"),anchor=source.save().resolve(WorldSaveIdentity.ANCHOR_FILE);
            if(!containsWorld){boolean pending=false;if(Files.isRegularFile(dbPath,LinkOption.NOFOLLOW_LINKS))try(var db=WorldReopenPlans.open(dbPath,true)){pending=WorldReopenPlans.pendingAt(db,DataPackInstallStore.pathKey(source.save()));}if(!pending)return initial;}
            if(Files.isRegularFile(dbPath,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(anchor,LinkOption.NOFOLLOW_LINKS)){
                UUID world=identity(source.save());String requested=System.getProperty(CANCEL_PROPERTY,"").strip();
                if(!requested.isEmpty()){
                    UUID operation;try{operation=UUID.fromString(requested);}catch(IllegalArgumentException invalid){throw new IllegalStateException("WORLD_REOPEN_CANCEL_ARGUMENT");}
                    try(var db=WorldReopenPlans.open(dbPath,false)){
                        var pending=WorldReopenPlans.pending(db,world);if(pending.isPresent()&&pending.get().input().operation().equals(operation)){cancellable(new PlanContext(source.save(),pending.get()));WorldReopenPlans.cancel(db,world,operation,null,"JVM_STARTUP_OPERATOR");}
                    }
                }
                try(var db=WorldReopenPlans.open(dbPath,true)){
                    var pending=WorldReopenPlans.pending(db,world);
                    if(pending.isPresent()){
                        var p=pending.get();if(!p.savePath().equals(DataPackInstallStore.pathKey(source.save())))throw new IllegalStateException("WORLD_REOPEN_SAVE_CHANGED");
                        if(p.state().equals("CANCEL_REQUESTED")){
                            cancellable(new PlanContext(source.save(),p));
                            var now=initial.dataPacks();if(!same(now,p.desiredEnabled(),p.desiredDisabled())&&!same(now,p.beforeEnabled(),p.beforeDisabled()))throw new IllegalStateException("WORLD_REOPEN_SELECTION_CONFLICT");
                            config=new WorldDataConfiguration(new DataPackConfig(p.beforeEnabled(),p.beforeDisabled()),initial.enabledFeatures());
                        }else if(!safeMode){
                            if(!p.state().equals("WAIT_REOPEN"))throw new IllegalStateException("WORLD_REOPEN_RECOVERY_REQUIRED");
                            if(!same(initial.dataPacks(),p.desiredEnabled(),p.desiredDisabled()))throw new IllegalStateException("WORLD_REOPEN_SELECTION_CONFLICT");
                            if(source.id().equals(p.blockedSource())||NativeDataPackRuntime.liveWorld(source.save()))throw new IllegalStateException("WORLD_REOPEN_REQUIRED");
                            if(!p.input().environment().equals(NativePackageCompatibility.observe().fingerprint()))throw new IllegalStateException("WORLD_REOPEN_ENVIRONMENT_CHANGED");
                        }
                    }
                }
            }else if(containsWorld)throw new IllegalStateException("WORLD_REOPEN_SAVE_IDENTITY_REQUIRED");
            if(!containsWorld)return config;
            if(EXPECTED.size()>=512&&!EXPECTED.containsKey(source.id()))throw new IllegalStateException("WORLD_REOPEN_SOURCE_BUDGET");
            EXPECTED.put(source.id(),new Expected(DataPackInstallStore.pathKey(source.save()),safeMode?Set.of():Set.copyOf(config.dataPacks().getEnabled())));return config;
        }catch(Exception failure){if(failure instanceof IllegalStateException expected)throw expected;throw new IllegalStateException("WORLD_REOPEN_BOOTSTRAP_UNAVAILABLE",failure);}
    }
    public static void serverConstructed(net.minecraft.server.MinecraftServer server){
        try{
            Path save=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toRealPath();var ids=server.getResourceManager().listPacks().map(net.minecraft.server.packs.PackResources::packId).toList();if(ids.stream().noneMatch(id->id.startsWith("file/mineagent-world-")))return;
            UUID world=identity(save);try(var db=WorldReopenPlans.open(home().resolve("runtime.db"),false)){var pending=WorldReopenPlans.pending(db,world);if(pending.isPresent()&&ids.contains("file/"+pending.get().filename()))WorldReopenPlans.started(db,world,pending.get().input().operation());}
        }catch(Exception failure){throw new IllegalStateException("WORLD_REOPEN_START_MARKER_UNAVAILABLE",failure);}
    }
    public static Path worldgenPath(Path save){return net.minecraft.world.level.levelgen.WorldGenSettings.TYPE.id().withSuffix(".dat").resolveAgainst(save.resolve("data"));}
    public static String worldgenHash(Path save)throws Exception{Path path=worldgenPath(save);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IllegalStateException("WORLD_REOPEN_WORLDGEN_SAVE_MISSING");try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(64*1024*1024+1);if(bytes.length>64*1024*1024)throw new IllegalStateException("WORLD_REOPEN_WORLDGEN_SAVE_LIMIT");return RuntimePackageCanonicalizer.sha256(bytes);}}
    public static void cancellable(PlanContext context)throws Exception{if(context.plan().nativeStarted()||!worldgenHash(context.save()).equals(context.plan().worldgenHash()))throw new IllegalStateException("WORLD_REOPEN_MAY_HAVE_LOADED_NOT_A_RETIREMENT");}
    public record PlanContext(Path save,WorldReopenPlans.Plan plan){}
    public static boolean same(DataPackConfig actual,List<String> enabled,List<String> disabled){return actual.getEnabled().equals(enabled)&&actual.getDisabled().equals(disabled);}
    public static UUID identity(Path save)throws Exception{
        Path home=home().toRealPath();Path file=save.resolve(WorldSaveIdentity.ANCHOR_FILE);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)>4096)throw new IllegalStateException("WORLD_REOPEN_SAVE_IDENTITY_REQUIRED");var anchor=JSON.readValue(Files.readAllBytes(file),WorldSaveIdentity.Anchor.class);
        try(var db=WorldReopenPlans.open(home.resolve("world-identities.db"),true);var q=db.prepareStatement("SELECT scope_id,token,save_path,home_root,state FROM identity_bindings_v1 WHERE save_id=?")){
            q.setString(1,anchor.saveId().toString());try(var r=q.executeQuery()){if(!r.next()||!anchor.scopeId().toString().equals(r.getString(1))||!anchor.token().toString().equals(r.getString(2))||!DataPackInstallStore.pathKey(save.toRealPath()).equals(r.getString(3))||!DataPackInstallStore.pathKey(home).equals(r.getString(4))||!r.getString(5).equals("BOUND"))throw new IllegalStateException("WORLD_REOPEN_SAVE_CHANGED");}
        }return anchor.scopeId();
    }
    public static boolean managedWorld(Path save){
        try{String key=DataPackInstallStore.pathKey(save.toRealPath());if(MANAGED.contains(key))return true;Path dbPath=home().resolve("runtime.db");if(!Files.isRegularFile(dbPath)||!Files.isRegularFile(save.resolve(WorldSaveIdentity.ANCHOR_FILE)))return false;UUID world=identity(save);
            try(var db=WorldReopenPlans.open(dbPath,true);var q=db.prepareStatement("SELECT 1 FROM "+DataPackInstallStore.ARTIFACTS+" WHERE world=? AND filename LIKE 'mineagent-world-%' LIMIT 1")){q.setString(1,world.toString());try(var r=q.executeQuery()){return r.next();}}
        }catch(Exception ignored){return MANAGED.contains(DataPackInstallStore.pathKey(save));}
    }
    public static List<WorldReopenPlans.Plan> recoveryPlans(int offset)throws Exception{Path db=home().resolve("runtime.db");if(!Files.isRegularFile(db))return List.of();try(var connection=WorldReopenPlans.open(db,true)){return WorldReopenPlans.listPending(connection,offset);}}
    /** Local operator only, outside any server. Takes the actual save lock, changes approval metadata, never rewrites player/world NBT. */
    public static void requestOfflineCancel(WorldReopenPlans.Plan expected,Path localSaves,String actor)throws Exception{
        Path base=localSaves.toRealPath(),save=Path.of(expected.savePath()).toRealPath();if(!save.startsWith(base)||save.equals(base))throw new SecurityException("WORLD_REOPEN_LOCAL_SAVE_ONLY");
        UUID world=identity(save);if(!world.equals(expected.world()))throw new IllegalStateException("WORLD_REOPEN_SAVE_CHANGED");
        Path lockPath=save.resolve("session.lock");if(Files.exists(lockPath,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(lockPath,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(lockPath)))throw new IllegalStateException("WORLD_REOPEN_SAVE_LOCK");
        try(var channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IllegalStateException("WORLD_REOPEN_WORLD_IN_USE");cancellable(new PlanContext(save,expected));if(!identity(save).equals(world))throw new IllegalStateException("WORLD_REOPEN_SAVE_CHANGED");try(var db=WorldReopenPlans.open(home().resolve("runtime.db"),false)){var current=WorldReopenPlans.get(db,world,expected.input().operation());if(current.revision()!=expected.revision())throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");WorldReopenPlans.cancel(db,world,current.input().operation(),null,actor);}
        }catch(OverlappingFileLockException busy){throw new IllegalStateException("WORLD_REOPEN_WORLD_IN_USE");}
    }
}
