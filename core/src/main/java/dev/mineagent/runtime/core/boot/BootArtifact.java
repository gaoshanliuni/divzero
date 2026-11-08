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
public record BootArtifact(Metadata metadata,String hash,Set<String> classPackages,Proof proof,byte[] signedBytes) {
    public static final String METADATA="META-INF/mineagent/boot.json";
    public static final String ATTESTATION="META-INF/mineagent/boot-proof.json";
    public record Proof(UUID build,String resourceId,String publicKey,String signature){public Proof{Objects.requireNonNull(build);dev.mineagent.runtime.api.packages.ResourceIds.require(resourceId);if(publicKey==null||publicKey.length()>4096||signature==null||signature.length()>256)throw new IllegalArgumentException("BOOT_PROOF_FORMAT");}}
    public record Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,
            String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) BootReplacement replacement,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) BootDependencyGraph dependencies,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String artifactId) {
        public Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus,BootReplacement replacement,BootDependencyGraph dependencies){this(schema,manifest,modId,entrypoint,bootstrap,nativeClasspath,environment,physicalSide,mappingStatus,replacement,dependencies,"");}
        public Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus,BootReplacement replacement){this(schema,manifest,modId,entrypoint,bootstrap,nativeClasspath,environment,physicalSide,mappingStatus,replacement,null);}
        public Metadata(int schema,RuntimePackage manifest,String modId,String entrypoint,String bootstrap,String nativeClasspath,NativeCompatibilityPolicy.Environment environment,String physicalSide,String mappingStatus){this(schema,manifest,modId,entrypoint,bootstrap,nativeClasspath,environment,physicalSide,mappingStatus,null);}
        public Metadata {
            artifactId=artifactId==null?"":artifactId;if(schema==2)dev.mineagent.runtime.api.packages.ResourceIds.require(artifactId);else if(schema!=1||!artifactId.isEmpty())throw new IllegalArgumentException("BOOT_ARTIFACT_METADATA");
            if(manifest==null||modId==null||!modId.matches("[a-z][a-z0-9_]{1,63}")||entrypoint==null||bootstrap==null||nativeClasspath==null||!nativeClasspath.matches("[a-f0-9]{64}")||environment==null||!Set.of("CLIENT","SERVER").contains(physicalSide)||!"RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS".equals(mappingStatus))throw new IllegalArgumentException("BOOT_ARTIFACT_METADATA");
            if(!bootstrap.equals("dev.mineagent.boot.p"+manifest.packageId().toString().replace("-","")+".Bootstrap"))throw new IllegalArgumentException("BOOT_BOOTSTRAP_IDENTITY");
            BootExtensionPlan.validate(manifest);(dependencies==null?BootDependencyGraph.empty():dependencies).require(manifest);if(dependencies!=null&&dependencies.nodes().stream().anyMatch(n->n.modId().equals(modId)))throw new IllegalArgumentException("BOOT_DEPENDENCY_MOD_ID_CONFLICT");if(replacement!=null&&(!replacement.packageId().equals(manifest.packageId())||!replacement.modId().equals(modId)||replacement.canonical().equals(manifest.canonicalSha256())))throw new IllegalArgumentException("BOOT_REPLACEMENT_SOURCE");
        }
    }
    public BootArtifact{classPackages=Set.copyOf(classPackages);signedBytes=signedBytes==null?new byte[0]:signedBytes.clone();}
    @Override public byte[] signedBytes(){return signedBytes.clone();}
    public static BootArtifact inspect(byte[] image)throws Exception {
        return inspect(image,"");
    }
    /** Legacy identities must come from an existing approved record, never a newly calculated content digest. */
    public static BootArtifact inspect(byte[] image,String legacyId)throws Exception {
        if(image.length<1||image.length>BootExtensionPlan.MAX_ARCHIVE)throw new IllegalStateException("BOOT_ARCHIVE_LIMIT");
        Metadata metadata=null;Proof proof=null;var entries=new TreeMap<String,byte[]>();var names=new HashSet<String>();var packages=new HashSet<String>();long total=0;boolean toml=false;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(image))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String name=entry.getName();
            if(entry.isDirectory()||name.startsWith("/")||name.contains("\\")||name.length()>512||Arrays.stream(name.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals(".."))||!names.add(name)||names.size()>4608)throw new IllegalStateException("BOOT_ARCHIVE_ENTRY");
            byte[] bytes=zip.readNBytes(8*1024*1024+1);total+=bytes.length;if(bytes.length>8*1024*1024||total>24*1024*1024)throw new IllegalStateException("BOOT_ARCHIVE_LIMIT");
            if(name.equals(METADATA)){if(bytes.length>1024*1024)throw new IllegalStateException("BOOT_ARTIFACT_METADATA");metadata=new ObjectMapper().readValue(bytes,Metadata.class);}
            if(name.equals(ATTESTATION)){if(bytes.length>8192)throw new IllegalStateException("BOOT_PROOF_FORMAT");proof=new ObjectMapper().readValue(bytes,Proof.class);}else entries.put(name,bytes);
            if(name.equals("META-INF/neoforge.mods.toml"))toml=true;
            if(name.endsWith(".class")){var model=java.lang.classfile.ClassFile.of().parse(bytes);String cls=model.thisClass().asInternalName();if(!name.equals(cls+".class"))throw new IllegalStateException("BOOT_CLASS_PATH");int slash=cls.lastIndexOf('/');packages.add(slash<0?"":cls.substring(0,slash));}
        }}
        if(metadata==null||!toml||!names.contains(metadata.bootstrap().replace('.','/')+".class")||!names.contains(metadata.entrypoint().replace('.','/')+".class"))throw new IllegalStateException("BOOT_ARCHIVE_INCOMPLETE");
        String id=metadata.schema()==2?metadata.artifactId():legacyId;
        if(id.isEmpty())throw new IllegalStateException("BOOT_LEGACY_ID_REQUIRED");dev.mineagent.runtime.api.packages.ResourceIds.require(id);
        byte[] signed=proof==null?new byte[0]:signingBytes(proof.build(),proof.resourceId(),proof.publicKey(),entries);
        if(proof!=null&&(!proof.resourceId().equals(id)||!IdentitySigner.verify(Base64.getDecoder().decode(proof.publicKey()),signed,Base64.getDecoder().decode(proof.signature()))))throw new IllegalStateException("BOOT_ARTIFACT_SIGNATURE");
        return new BootArtifact(metadata,id,packages,proof,signed);
    }
    public void verify(byte[] key,String packageHash,String snapshotHash,String environment)throws Exception {
        verifySource(key,packageHash,snapshotHash,environment);
        if(metadata.schema()==2&&(proof==null||!Arrays.equals(key,Base64.getDecoder().decode(proof.publicKey()))||!IdentitySigner.verify(key,signedBytes,Base64.getDecoder().decode(proof.signature()))))throw new IllegalStateException("BOOT_ARTIFACT_SIGNATURE");
    }
    /** Bind an archive to the exact persisted build, including its signing identity and operation. */
    public void verifyBuild(BootInstallStore.Build build)throws Exception {
        if(!hash.equals(build.artifact())||!metadata.manifest().equals(build.manifest())||!metadata.modId().equals(build.modId())
                ||proof!=null&&!proof.build().equals(build.id()))throw new IllegalStateException("BOOT_BUILD_CONTEXT_CHANGED");
        verify(Base64.getDecoder().decode(build.publicKey()),build.manifest().canonicalSha256(),build.nativeClasspath(),build.environment());
    }
    public void verifySource(byte[] key,String packageHash,String snapshotHash,String environment)throws Exception {
        var pkg=metadata.manifest();if(!pkg.canonicalSha256().equals(packageHash)||!dev.mineagent.runtime.core.packages.PackageSignatures.verifyManifest(pkg,key))throw new IllegalStateException("BOOT_PACKAGE_SIGNATURE");
        if(!metadata.nativeClasspath().equals(snapshotHash)||metadata.schema()==2&&!metadata.environment().fingerprint().equals(environment))throw new IllegalStateException("BOOT_BUILD_CONTEXT_CHANGED");
    }
    public static byte[] seal(byte[] draft,UUID build,IdentitySigner signer)throws Exception{
        var inspected=inspect(draft);if(inspected.metadata().schema()!=2||inspected.proof()!=null)throw new IllegalStateException("BOOT_DRAFT_REQUIRED");
        var entries=new TreeMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(draft))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)entries.put(entry.getName(),zip.readAllBytes());}
        String key=Base64.getEncoder().encodeToString(signer.publicKeyEncoded());String id=inspected.hash();var signature=signer.sign(signingBytes(build,id,key,entries));
        entries.put(ATTESTATION,new ObjectMapper().writeValueAsBytes(new Proof(build,id,key,Base64.getEncoder().encodeToString(signature))));
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var entry:entries.entrySet()){var file=new ZipEntry(entry.getKey());file.setTime(0);zip.putNextEntry(file);zip.write(entry.getValue());zip.closeEntry();}}
        byte[] result=output.toByteArray();inspect(result).verify(signer.publicKeyEncoded(),inspected.metadata().manifest().canonicalSha256(),inspected.metadata().nativeClasspath(),inspected.metadata().environment().fingerprint());return result;
    }
    private static byte[] signingBytes(UUID build,String id,String key,Map<String,byte[]> entries)throws IOException{
        var output=new ByteArrayOutputStream();try(var data=new DataOutputStream(output)){data.writeUTF("DIVZERO_BOOT_ARTIFACT_V2");data.writeUTF(build.toString());data.writeUTF(id);data.writeUTF(key);for(var entry:entries.entrySet()){data.writeUTF(entry.getKey());data.writeInt(entry.getValue().length);data.write(entry.getValue());}}return output.toByteArray();
    }
}
