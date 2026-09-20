package dev.mineagent.runtime.core.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Structural archive/provenance verification, never a claim about the extension's gameplay effects. */
public record BootArtifact(Metadata metadata,String hash,Set<String> classPackages) {
    public static final String METADATA="META-INF/mineagent/boot.json";
    public record Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,
            String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) BootReplacement replacement,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) BootDependencyGraph dependencies) {
        public Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus,BootReplacement replacement){this(schema,manifest,modId,entrypoint,bootstrap,nativeClasspath,environment,physicalSide,mappingStatus,replacement,null);}
        public Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus){this(schema,manifest,modId,entrypoint,bootstrap,nativeClasspath,environment,physicalSide,mappingStatus,null);}
        public Metadata {
            if(schema!=1||manifest==null||modId==null||!modId.matches("[a-z][a-z0-9_]{1,63}")||entrypoint==null||bootstrap==null||nativeClasspath==null||!nativeClasspath.matches("[a-f0-9]{64}")||environment==null||!Set.of("CLIENT","SERVER").contains(physicalSide)||!"RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS".equals(mappingStatus))throw new IllegalArgumentException("BOOT_ARTIFACT_METADATA");
            if(!bootstrap.equals("dev.mineagent.boot.p"+manifest.packageId().toString().replace("-","")+".Bootstrap"))throw new IllegalArgumentException("BOOT_BOOTSTRAP_IDENTITY");
            BootExtensionPlan.validate(manifest);(dependencies==null?BootDependencyGraph.empty():dependencies).require(manifest);if(dependencies!=null&&dependencies.nodes().stream().anyMatch(n->n.modId().equals(modId)))throw new IllegalArgumentException("BOOT_DEPENDENCY_MOD_ID_CONFLICT");if(replacement!=null&&(!replacement.packageId().equals(manifest.packageId())||!replacement.modId().equals(modId)||replacement.canonical().equals(manifest.canonicalSha256())))throw new IllegalArgumentException("BOOT_REPLACEMENT_SOURCE");
        }
    }
    public BootArtifact{classPackages=Set.copyOf(classPackages);}
    public static BootArtifact inspect(byte[] image)throws Exception {
        if(image.length<1||image.length>BootExtensionPlan.MAX_ARCHIVE)throw new IllegalStateException("BOOT_ARCHIVE_LIMIT");
        Metadata metadata=null;var names=new HashSet<String>();var packages=new HashSet<String>();long total=0;boolean toml=false;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(image))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String name=entry.getName();
            if(entry.isDirectory()||name.startsWith("/")||name.contains("\\")||name.length()>512||Arrays.stream(name.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals(".."))||!names.add(name)||names.size()>4608)throw new IllegalStateException("BOOT_ARCHIVE_ENTRY");
            byte[] bytes=zip.readNBytes(8*1024*1024+1);total+=bytes.length;if(bytes.length>8*1024*1024||total>24*1024*1024)throw new IllegalStateException("BOOT_ARCHIVE_LIMIT");
            if(name.equals(METADATA)){if(bytes.length>1024*1024)throw new IllegalStateException("BOOT_ARTIFACT_METADATA");metadata=new ObjectMapper().readValue(bytes,Metadata.class);}
            if(name.equals("META-INF/neoforge.mods.toml"))toml=true;
            if(name.endsWith(".class")){var model=java.lang.classfile.ClassFile.of().parse(bytes);String cls=model.thisClass().asInternalName();if(!name.equals(cls+".class"))throw new IllegalStateException("BOOT_CLASS_PATH");int slash=cls.lastIndexOf('/');packages.add(slash<0?"":cls.substring(0,slash));}
        }}
        if(metadata==null||!toml||!names.contains(metadata.bootstrap().replace('.','/')+".class")||!names.contains(metadata.entrypoint().replace('.','/')+".class"))throw new IllegalStateException("BOOT_ARCHIVE_INCOMPLETE");
        return new BootArtifact(metadata,RuntimePackageCanonicalizer.sha256(image),packages);
    }
    public void verify(byte[] key,String packageHash,String snapshotHash,String environment)throws Exception {
        var pkg=metadata.manifest();if(!pkg.canonicalSha256().equals(packageHash)||!RuntimePackageCanonicalizer.sha256(pkg).equals(packageHash)||!IdentitySigner.verify(key,packageHash.getBytes(StandardCharsets.US_ASCII),Base64.getDecoder().decode(pkg.signature())))throw new IllegalStateException("BOOT_PACKAGE_SIGNATURE");
        if(!metadata.nativeClasspath().equals(snapshotHash)||!metadata.environment().fingerprint().equals(environment))throw new IllegalStateException("BOOT_BUILD_CONTEXT_CHANGED");
    }
}
