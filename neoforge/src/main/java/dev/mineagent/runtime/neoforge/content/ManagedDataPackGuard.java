package dev.mineagent.runtime.neoforge.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.persistence.WorldSaveIdentity;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.io.*;

/** Narrow discovery/open gate for this installer's reserved file names. It never initializes world services or reads secrets. */
public final class ManagedDataPackGuard {
    private static final ObjectMapper JSON=new ObjectMapper();private static final Map<String,UUID> LIVE=new java.util.concurrent.ConcurrentHashMap<>();private static final Map<String,String> ERRORS=new java.util.concurrent.ConcurrentHashMap<>();
    public record Proof(UUID world,UUID source,String filename,String hash,boolean worldPack,boolean bootstrapOpen){}
    private record Admission(DataPackPlan.Snapshot snapshot,Proof proof){}
    public static Optional<Proof> proof(PackResources resources){return resources instanceof SnapshotResources snapshot?Optional.of(snapshot.proof):Optional.empty();}
    private static volatile boolean hooked;
    private ManagedDataPackGuard(){}
    public static void hooked(){hooked=true;}public static boolean ready(){return hooked;}
    public static AutoCloseable permit(Path file,UUID operation){String key=DataPackInstallStore.pathKey(file);if(LIVE.putIfAbsent(key,operation)!=null)throw new IllegalStateException("DATA_PACK_BUSY");return ()->LIVE.remove(key,operation);}
    public static String error(Path file){return ERRORS.getOrDefault(DataPackInstallStore.pathKey(file),"");}
    public static Pack create(Path folder,PackLocationInfo info,Pack.ResourcesSupplier ordinary,PackType type,PackSelectionConfig selection,UUID source){
        if(type!=PackType.SERVER_DATA||!(info.id().startsWith("file/"+DataPackPlan.PREFIX)||info.id().startsWith("file/mineagent-world-")))return Pack.readMetaAndCreate(info,ordinary,type,selection);
        String name=info.id().substring(5);if(!DataPackPlan.managedName(name))return null;Path file=folder.resolve(name);
        try{
            var location=new PackLocationInfo(info.id(),info.title(),PackSource.create(PackSource.NO_DECORATION,false),info.knownPackInfo());
            var supplier=new Pack.ResourcesSupplier(){
                @Override public PackResources openPrimary(PackLocationInfo location){return open(file,location,source,false);}
                @Override public PackResources openFull(PackLocationInfo location,Pack.Metadata metadata){return open(file,location,source,true);}
            };
            return Pack.readMetaAndCreate(location,supplier,type,selection);
        }catch(RuntimeException rejected){if(name.startsWith("mineagent-world-")&&WorldReopenBootstrap.expected(source,info.id()))throw rejected;return null;}
    }
    /** Actual Minecraft metadata parser over verified bytes; never registered with a repository or used as boot evidence. */
    public static Pack metadata(DataPackPlan plan,Path file,UUID world,UUID source)throws Exception{
        var snapshot=plan.snapshot(file);var info=new PackLocationInfo(plan.packId(),net.minecraft.network.chat.Component.literal(plan.manifest().name()),PackSource.create(PackSource.NO_DECORATION,false),Optional.empty());
        var proof=new Proof(world,source,plan.filename(),snapshot.hash(),plan.reopensWorld(),false);
        return Pack.readMetaAndCreate(info,new Pack.ResourcesSupplier(){public PackResources openPrimary(PackLocationInfo location){return new SnapshotResources(location,snapshot.files(),proof);}public PackResources openFull(PackLocationInfo location,Pack.Metadata meta){return new SnapshotResources(location,snapshot.files(),proof);}},PackType.SERVER_DATA,new PackSelectionConfig(false,Pack.Position.TOP,false));
    }
    private static PackResources open(Path file,PackLocationInfo location,UUID source,boolean full){
        try{var snapshot=admit(file,source,full);ERRORS.remove(DataPackInstallStore.pathKey(file));return new SnapshotResources(location,snapshot.snapshot().files(),snapshot.proof());}
        catch(Exception failure){String code=Objects.toString(failure.getMessage(),"");if(!code.matches("(?:WORLD_REOPEN|DATA_PACK|NATIVE_COMPATIBILITY|NATIVE_ENVIRONMENT)_[A-Z0-9_]{1,64}"))code="DATA_PACK_ADMISSION_FAILED";if(ERRORS.size()<512)ERRORS.put(DataPackInstallStore.pathKey(file),code);throw new IllegalStateException(code);}
    }
    private static Admission admit(Path file,UUID source,boolean full)throws Exception{
        Path save=file.getParent().getParent().toRealPath(),home=net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("mineagent-runtime-data").toRealPath();
        if(!file.getParent().toRealPath().equals(save.resolve("datapacks")))throw new IllegalStateException("DATA_PACK_DIRECTORY_LINK");
        Path anchorFile=save.resolve(WorldSaveIdentity.ANCHOR_FILE);var anchor=JSON.readValue(read(anchorFile,4096),WorldSaveIdentity.Anchor.class);
        try(var identity=readOnly(home.resolve("world-identities.db"));var q=identity.prepareStatement("SELECT scope_id,token,save_path,home_root,state FROM identity_bindings_v1 WHERE save_id=?")){
            q.setString(1,anchor.saveId().toString());try(var r=q.executeQuery()){if(!r.next()||!anchor.scopeId().toString().equals(r.getString(1))||!anchor.token().toString().equals(r.getString(2))||!DataPackInstallStore.pathKey(save).equals(r.getString(3))||!DataPackInstallStore.pathKey(home).equals(r.getString(4))||!r.getString(5).equals("BOUND"))throw new IllegalStateException("DATA_PACK_SAVE_IDENTITY");}
        }
        byte[] publicKey=read(home.resolve("identity/identity-public.x509"),4096);DataPackInstallStore.Artifact artifact;
        try(var db=readOnly(home.resolve("runtime.db"))){
            try(var q=db.prepareStatement("SELECT payload FROM "+DataPackInstallStore.ARTIFACTS+" WHERE world=? AND filename=?")){q.setString(1,anchor.scopeId().toString());q.setString(2,file.getFileName().toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("DATA_PACK_NOT_APPROVED");String raw=r.getString(1);if(raw.length()>512*1024)throw new IllegalStateException("DATA_PACK_RECORD_LIMIT");artifact=JSON.readValue(raw,DataPackInstallStore.Artifact.class);}}
            if(!artifact.world().equals(anchor.scopeId())||!artifact.savePath().equals(DataPackInstallStore.pathKey(save)))throw new IllegalStateException("DATA_PACK_SAVE_IDENTITY");
            boolean worldPack=artifact.manifest().activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.WORLD_REOPEN;
            if(worldPack&&artifact.state().equals("WAIT_REOPEN")){
                var pending=WorldReopenPlans.pending(db,artifact.world()).orElseThrow(()->new IllegalStateException("WORLD_REOPEN_PLAN_MISSING"));
                if(!pending.filename().equals(artifact.filename())||!pending.state().equals("WAIT_REOPEN")||!pending.input().environment().equals(NativePackageCompatibility.observe().fingerprint()))throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");
                if(full&&(source.equals(pending.blockedSource())||NativeDataPackRuntime.liveWorld(save)||!WorldReopenBootstrap.bootstrapOpen(source,save,artifact.filename())))throw new IllegalStateException("WORLD_REOPEN_REQUIRED");
            }else if(!artifact.state().equals("ENABLED")){
                UUID live=LIVE.get(DataPackInstallStore.pathKey(file));if(!artifact.state().equals("DISPATCHING")||live==null)throw new IllegalStateException("DATA_PACK_NOT_APPROVED");
                try(var q=db.prepareStatement("SELECT payload FROM "+DataPackInstallStore.JOBS+" WHERE world=? AND id=?")){q.setString(1,anchor.scopeId().toString());q.setString(2,live.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("DATA_PACK_NOT_APPROVED");var job=JSON.readValue(r.getString(1),DataPackInstallStore.Job.class);if(!job.phase().equals("DISPATCHING")||!job.input().owner().equals(artifact.owner())||!job.input().pkg().equals(artifact.manifest().packageId())||!job.input().canonical().equals(artifact.manifest().canonicalSha256()))throw new IllegalStateException("DATA_PACK_NOT_APPROVED");}}
            }
            for(var pair:Map.of("RUN_CODE",artifact.runGeneration(),"MANAGE_PACKAGES",artifact.manageGeneration()).entrySet())try(var q=db.prepareStatement("SELECT generation FROM mineagent_permission_generations_v1 WHERE id=?")){q.setString(1,artifact.owner()+"|"+pair.getKey());try(var r=q.executeQuery()){if((r.next()?r.getLong(1):0)!=pair.getValue())throw new IllegalStateException("DATA_PACK_PERMISSION_CHANGED");}}
            RuntimePackage head=head(db,artifact.manifest().packageId(),publicKey);if(!worldPack&&artifact.state().equals("ENABLED")&&!head.enabled())throw new IllegalStateException("DATA_PACK_SOURCE_DISABLED");if(!worldPack&&!head.canonicalSha256().equals(artifact.manifest().canonicalSha256()))throw new IllegalStateException("DATA_PACK_SOURCE_CHANGED");
            for(var dependency:artifact.manifest().dependencies().entrySet()){var current=head(db,dependency.getKey(),publicKey);if(!current.enabled()||!current.version().equals(dependency.getValue()))throw new IllegalStateException("DATA_PACK_DEPENDENCY_CHANGED");}
        }
        var plan=DataPackPlan.inspect(artifact.manifest());if(!plan.filename().equals(file.getFileName().toString()))throw new IllegalStateException("DATA_PACK_RECORD_CONTEXT");
        plan.verifySignature(publicKey);
        var result=NativeCompatibilityPolicy.check(artifact.manifest(),NativePackageCompatibility.observe(),"SERVER");if(!result.allowed())throw new IllegalStateException(result.code());
        validateReloadTargets(plan);
        var snapshot=plan.snapshot(file);if(!snapshot.hash().equals(artifact.zipHash()))throw new IllegalStateException("DATA_PACK_ARCHIVE_HASH");return new Admission(snapshot,new Proof(artifact.world(),source,artifact.filename(),snapshot.hash(),plan.reopensWorld(),full&&WorldReopenBootstrap.bootstrapOpen(source,save,artifact.filename())));
    }
    public static void validateReloadTargets(DataPackPlan plan){
        if(plan.reopensWorld())return;
        var keys=net.neoforged.neoforge.registries.DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().toList();
        for(var path:plan.files().keySet())if(path.startsWith("data/")){int slash=path.indexOf('/',5);String resource=path.substring(slash+1);for(var entry:keys)if(resource.startsWith(entry.key().identifier().getPath()+"/"))throw new IllegalStateException("DATA_PACK_WORLD_REOPEN_REQUIRED");}
    }
    private static RuntimePackage head(Connection db,UUID id,byte[] publicKey)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM mineagent_runtime_records WHERE world_id=? AND namespace='runtime_packages_v2' AND record_id=? AND deleted=0")){q.setString(1,RuntimePackageLibrary.GLOBAL_LIBRARY_ID.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("DATA_PACK_SOURCE_CHANGED");String raw=r.getString(1);if(raw.length()>512*1024)throw new IllegalStateException("DATA_PACK_RECORD_LIMIT");var head=JSON.readValue(raw,RuntimePackage.class);if(!head.packageId().equals(id)||!RuntimePackageCanonicalizer.sha256(head).equals(head.canonicalSha256())||!dev.mineagent.runtime.core.crypto.IdentitySigner.verify(publicKey,head.canonicalSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),Base64.getDecoder().decode(head.signature())))throw new IllegalStateException("DATA_PACK_SIGNATURE");return head;}}}
    private static byte[] read(Path file,int max)throws Exception{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("DATA_PACK_FILE_LINK");try(var in=Files.newInputStream(file)){byte[] bytes=in.readNBytes(max+1);if(bytes.length>max)throw new IllegalStateException("DATA_PACK_RECORD_LIMIT");return bytes;}}
    private static Connection readOnly(Path path)throws Exception{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IllegalStateException("DATA_PACK_STORE_MISSING");var db=DriverManager.getConnection("jdbc:sqlite:"+path.toUri()+"?mode=ro");try{try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=1500");}return db;}catch(Exception failure){db.close();throw failure;}}
    private static final class SnapshotResources extends AbstractPackResources {
        private Map<String,byte[]> files;private final Set<String> namespaces;private final Proof proof;
        SnapshotResources(PackLocationInfo location,Map<String,byte[]> files,Proof proof){super(location);this.files=files;this.proof=proof;var names=new HashSet<String>();for(var p:files.keySet())if(p.startsWith("data/"))names.add(p.substring(5,p.indexOf('/',5)));namespaces=Set.copyOf(names);}
        private IoSupplier<InputStream> resource(String key){byte[] data=files.get(key);return data==null?null:()->new ByteArrayInputStream(data);}
        @Override public IoSupplier<InputStream> getRootResource(String... path){return resource(String.join("/",path));}
        @Override public IoSupplier<InputStream> getResource(PackType type,Identifier id){return type==PackType.SERVER_DATA?resource("data/"+id.getNamespace()+"/"+id.getPath()):null;}
        @Override public void listResources(PackType type,String namespace,String directory,PackResources.ResourceOutput output){if(type!=PackType.SERVER_DATA)return;String root="data/"+namespace+"/",prefix=root+(directory.isEmpty()?"":directory+"/");for(var key:files.keySet())if(key.startsWith(prefix))output.accept(Identifier.fromNamespaceAndPath(namespace,key.substring(root.length())),resource(key));}
        @Override public Set<String> getNamespaces(PackType type){return type==PackType.SERVER_DATA?namespaces:Set.of();}
        @Override public void close(){files=Map.of();}
    }
}
