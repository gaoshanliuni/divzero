package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.client.resources.LocalResourcePackStore;
import dev.mineagent.runtime.client.trust.ServerTrustStore;
import dev.mineagent.runtime.core.packages.*;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.resources.Identifier;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** CLIENT resource discovery/open gate with no references to Minecraft client-only classes. */
public final class ManagedClientResourcePackGuard {
    private static final Map<String,UUID> LIVE=new java.util.concurrent.ConcurrentHashMap<>();private static volatile boolean hooked;
    private ManagedClientResourcePackGuard(){}
    public static void hooked(){hooked=true;}public static boolean ready(){return hooked;}
    public static AutoCloseable permit(String filename,UUID operation){if(LIVE.putIfAbsent(filename,operation)!=null)throw new IllegalStateException("RESOURCE_PACK_BUSY");return ()->LIVE.remove(filename,operation);}
    public static Pack create(Path folder,PackLocationInfo info,Pack.ResourcesSupplier ordinary,PackType type,PackSelectionConfig selection){
        if(type!=PackType.CLIENT_RESOURCES||!info.id().startsWith("file/"+ResourcePackPlan.PREFIX))return Pack.readMetaAndCreate(info,ordinary,type,selection);
        String filename=info.id().substring(5);if(!ResourcePackPlan.managedName(filename))return null;var location=new PackLocationInfo(info.id(),info.title(),PackSource.create(PackSource.NO_DECORATION,false),info.knownPackInfo());
        try{return Pack.readMetaAndCreate(location,new Pack.ResourcesSupplier(){public PackResources openPrimary(PackLocationInfo l){return open(folder.resolve(filename),l);}public PackResources openFull(PackLocationInfo l,Pack.Metadata metadata){return open(folder.resolve(filename),l);}},type,selection);}catch(RuntimeException failure){return null;}
    }
    public static NativeCompatibilityPolicy.Environment environment(){return NativePackageCompatibility.observe();}
    public static void trusted(LocalResourcePackStore.Asset asset)throws Exception{
        Path root=net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();var trust=new ServerTrustStore(root.resolve("config/mineagent-trusted-servers.properties"));
        if(!trust.trustedFingerprint(asset.serverId()).equals(asset.fingerprint()))throw new IllegalStateException("RESOURCE_PACK_TRUST_CHANGED");ResourcePackPlan.verify(asset.manifest(),(value,sig)->trust.verify(asset.serverId(),value,sig));
        String filename=ResourcePackPlan.inspect(asset.manifest()).filename(LocalResourcePackStore.publisher(asset.serverId(),asset.fingerprint()));if(!filename.equals(asset.filename()))throw new IllegalStateException("RESOURCE_PACK_IDENTITY");
        var check=NativeCompatibilityPolicy.check(asset.manifest(),environment(),"CLIENT");if(!check.allowed())throw new IllegalStateException(check.code());
    }
    private static PackResources open(Path file,PackLocationInfo location){
        try{
            var store=LocalResourcePackStore.get(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());var a=store.get(file.getFileName().toString());
            if(!a.globalConsent()||!Path.of(a.directory()).toRealPath().equals(file.getParent().toRealPath()))throw new IllegalStateException("RESOURCE_PACK_LOCAL_CONSENT_REQUIRED");
            if(!a.state().equals("ENABLED")){var live=LIVE.get(a.filename());if(!a.state().equals("DISPATCHING")||live==null||!store.job(live).filter(j->j.phase().equals("DISPATCHING")&&j.input().filename().equals(a.filename())).isPresent())throw new IllegalStateException("RESOURCE_PACK_NOT_APPROVED");}
            trusted(a);if(!a.environment().equals(environment().fingerprint()))throw new IllegalStateException("RESOURCE_PACK_ENVIRONMENT_CHANGED");byte[] bytes=ResourcePackPlan.readArchive(file);if(!RuntimePackageCanonicalizer.sha256(bytes).equals(a.archiveHash()))throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_HASH");return new Snapshot(location,ResourcePackPlan.inspect(a.manifest()).snapshot(bytes));
        }catch(Exception failure){throw new IllegalStateException("RESOURCE_PACK_OPEN_REJECTED",failure);}
    }
    public static Pack metadata(LocalResourcePackStore.Asset asset,byte[] archive)throws Exception{
        trusted(asset);var content=ResourcePackPlan.inspect(asset.manifest()).snapshot(archive);var location=new PackLocationInfo("file/"+asset.filename(),net.minecraft.network.chat.Component.literal(asset.manifest().name()),PackSource.create(PackSource.NO_DECORATION,false),Optional.empty());
        return Pack.readMetaAndCreate(location,new Pack.ResourcesSupplier(){public PackResources openPrimary(PackLocationInfo info){return new Snapshot(info,content);}public PackResources openFull(PackLocationInfo info,Pack.Metadata metadata){return new Snapshot(info,content);}},PackType.CLIENT_RESOURCES,new PackSelectionConfig(false,Pack.Position.TOP,false));
    }
    private static final class Snapshot extends AbstractPackResources {
        private Map<String,byte[]> files;private final Set<String> namespaces;
        Snapshot(PackLocationInfo location,Map<String,byte[]> files){super(location);this.files=files;var result=new HashSet<String>();for(String p:files.keySet())if(p.startsWith("assets/"))result.add(p.substring(7,p.indexOf('/',7)));namespaces=Set.copyOf(result);}
        private IoSupplier<InputStream> resource(String key){byte[] bytes=files.get(key);return bytes==null?null:()->new ByteArrayInputStream(bytes);}
        @Override public IoSupplier<InputStream> getRootResource(String... path){return resource(String.join("/",path));}
        @Override public IoSupplier<InputStream> getResource(PackType type,Identifier id){return type==PackType.CLIENT_RESOURCES?resource("assets/"+id.getNamespace()+"/"+id.getPath()):null;}
        @Override public void listResources(PackType type,String namespace,String directory,PackResources.ResourceOutput output){if(type!=PackType.CLIENT_RESOURCES)return;String root="assets/"+namespace+"/",prefix=root+(directory.isEmpty()?"":directory+"/");for(String path:files.keySet())if(path.startsWith(prefix))output.accept(Identifier.fromNamespaceAndPath(namespace,path.substring(root.length())),resource(path));}
        @Override public Set<String> getNamespaces(PackType type){return type==PackType.CLIENT_RESOURCES?namespaces:Set.of();}
        @Override public void close(){files=Map.of();}
    }
}
