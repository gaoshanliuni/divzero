package dev.mineagent.runtime.neoforge.boot;

import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** In-process constructor/source observation. Does not verify gameplay effects or sandbox arbitrary Native code. */
public final class NativeBootProof {
    public record Proof(Class<?> bootstrap,UUID packageId,String canonical,String path,String archiveHash){}
    private record Source(BootArtifact.Metadata metadata,String path,String hash){}
    private static final Map<String,Proof> INITIALIZED=new ConcurrentHashMap<>();
    private static final Map<String,Source> SOURCES=new ConcurrentHashMap<>();
    private NativeBootProof(){}
    private static Source source(String modId)throws Exception {
        var info=net.neoforged.fml.ModList.get().getModFileById(modId);if(info==null)throw new IllegalStateException("BOOT_LOADER_SOURCE_MISSING");var file=info.getFile().getFilePath().toRealPath();
        var root=net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("mineagent-runtime-data/boot-install");
        var image=BootFiles.inspect(file,root.resolve("cache"));if(!image.metadata().modId().equals(modId))throw new IllegalStateException("BOOT_LOADER_SOURCE_UNVERIFIED");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var receipts=new ArrayList<BootInstallStore.Build>();
        if(image.proof()!=null){var receipt=root.resolve("receipts").resolve(image.proof().build()+".json");receipts.add(json.readValue(NativeCompilationSnapshot.read(receipt,1024*1024),BootInstallStore.Build.class));}
        else try(var files=java.nio.file.Files.newDirectoryStream(root.resolve("receipts"),"*.json")){for(var receipt:files){var build=json.readValue(NativeCompilationSnapshot.read(receipt,1024*1024),BootInstallStore.Build.class);if(build.artifact().equals(image.hash()))receipts.add(build);}}
        boolean approved=false;for(var build:receipts)if(build.artifact().equals(image.hash())&&build.modId().equals(modId)&&java.nio.file.Path.of(build.directory()).resolve(BootFiles.filename(build)).toRealPath().equals(file)&&build.manifest().equals(image.metadata().manifest())){
            image.verify(Base64.getDecoder().decode(build.publicKey()),build.manifest().canonicalSha256(),build.nativeClasspath(),build.environment());approved=true;break;
        }
        if(!approved||!dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy.check(image.metadata().manifest(),dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe(),image.metadata().physicalSide()).allowed())throw new IllegalStateException("BOOT_LOADER_SOURCE_UNVERIFIED");
        return new Source(image.metadata(),file.toString(),image.hash());
    }
    /** FML's dependency futures must have completed; fail instead of waiting under Mod construction. */
    public static void requireDependencies(Class<?> bootstrap,String modId,String packageId,String canonical)throws Exception {
        var current=source(modId);var metadata=current.metadata();
        if(!metadata.bootstrap().equals(bootstrap.getName())||!metadata.manifest().packageId().toString().equals(packageId)||!metadata.manifest().canonicalSha256().equals(canonical))throw new IllegalStateException("BOOT_LOADER_SOURCE_UNVERIFIED");
        var graph=metadata.dependencies()==null?BootDependencyGraph.empty():metadata.dependencies();graph.require(metadata.manifest());
        var directory=net.neoforged.fml.loading.FMLPaths.MODSDIR.get().toRealPath();
        for(var dependency:graph.nodes()){
            var proof=INITIALIZED.get(dependency.modId());var loaded=SOURCES.get(dependency.modId());
            if(proof==null||loaded==null||!proof.archiveHash().equals(dependency.artifact())||!loaded.hash().equals(dependency.artifact())||!proof.packageId().equals(dependency.packageId())||!proof.canonical().equals(dependency.canonical())||!proof.path().equals(directory.resolve(dependency.filename()).toString()))throw new IllegalStateException("BOOT_DEPENDENCY_NOT_INITIALIZED");
            dependency.require(loaded.metadata(),loaded.hash());graph.requireEmbedded(loaded.metadata());
            if(!loaded.metadata().physicalSide().equals(metadata.physicalSide())||!dev.mineagent.runtime.core.packages.PackageSignatures.verifyManifest(loaded.metadata().manifest(),Base64.getDecoder().decode(dependency.publicKey())))throw new IllegalStateException("BOOT_DEPENDENCY_SOURCE_UNVERIFIED");
        }
    }
    public static void initialized(Class<?> bootstrap,String modId,String packageId,String canonical) {
        UUID pkg=UUID.fromString(packageId);
        if(!canonical.matches("[a-f0-9]{64}")||!modId.matches("[a-z][a-z0-9_]{1,63}")||!bootstrap.getName().equals("dev.mineagent.boot.p"+pkg.toString().replace("-","")+".Bootstrap"))throw new IllegalStateException("BOOT_INITIALIZATION_PROOF");
        Source loaded;try{loaded=source(modId);if(!loaded.metadata().manifest().packageId().equals(pkg)||!loaded.metadata().manifest().canonicalSha256().equals(canonical))throw new IllegalStateException("BOOT_LOADER_SOURCE_UNVERIFIED");}catch(Exception e){throw new IllegalStateException("BOOT_LOADER_SOURCE_UNVERIFIED",e);}
        SOURCES.put(modId,loaded);var proof=new Proof(bootstrap,pkg,canonical,loaded.path(),loaded.hash());if(INITIALIZED.putIfAbsent(modId,proof)!=null)throw new IllegalStateException("BOOT_INITIALIZATION_DUPLICATE");
    }
    public static Optional<Proof> get(String modId){return Optional.ofNullable(INITIALIZED.get(modId));}
}
