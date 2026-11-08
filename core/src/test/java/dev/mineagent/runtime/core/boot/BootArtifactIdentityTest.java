package dev.mineagent.runtime.core.boot;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.packages.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class BootArtifactIdentityTest {
    @TempDir Path root;
    private record Fixture(IdentitySigner signer,RuntimePackage pkg,BootArtifact.Metadata metadata,Map<String,byte[]> entries){}
    private Fixture fixture(int schema)throws Exception{
        var signer=IdentitySigner.open(root.resolve(UUID.randomUUID().toString()));UUID id=UUID.randomUUID();
        var files=Map.of(BootExtensionPlan.DESCRIPTOR,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),"boot/src/fixture/Extension.java","package fixture; public class Extension {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var resources=new LinkedHashMap<String,RuntimeResourceRef>();var bytes=new HashMap<String,byte[]>();
        for(var file:files.entrySet()){String resource=ResourceIds.create();resources.put(file.getKey(),new RuntimeResourceRef(file.getKey(),resource,RuntimeResourceSide.SERVER,"text/plain",file.getValue().length));bytes.put(resource,file.getValue());}
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"test",Map.of());
        var contract=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target(env.minecraft(),env.loader(),env.loaderVersion(),env.namespace(),25,Map.of())));
        var descriptor=resources.get(BootExtensionPlan.DESCRIPTOR);
        var draft=new RuntimePackage(id,RuntimePackageType.EXTENSION,"Boot identity fixture","1.0",ActivationMode.BOOT_EXTENSION,Map.of(),Set.of("RUN_CODE"),Map.of("boot",new RuntimeEntrypoint(descriptor.path(),descriptor.side(),descriptor.sha256())),Map.of(),resources,PackageOrigin.GENERATED,false,1,"0".repeat(64),"",1,contract);
        var pkg=PackageSignatures.signNew(draft,bytes::get,signer);String bootstrap="dev.mineagent.boot.p"+id.toString().replace("-","")+".Bootstrap";
        var meta=new BootArtifact.Metadata(schema,pkg,"fixture_boot","fixture.Extension",bootstrap,ResourceIds.create(),env,"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",null,null,schema==2?ResourceIds.create():"");
        var entries=new TreeMap<String,byte[]>();entries.put(BootArtifact.METADATA,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(meta));entries.put("META-INF/neoforge.mods.toml","# fixture".getBytes());
        for(String type:List.of(bootstrap,"fixture.Extension"))entries.put(type.replace('.','/')+".class",java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of(type),builder->builder.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC)));
        return new Fixture(signer,pkg,meta,entries);
    }
    private byte[] archive(Map<String,byte[]> entries)throws Exception{var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return out.toByteArray();}
    private Map<String,byte[]> entries(byte[] bytes)throws Exception{var result=new TreeMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),zip.readAllBytes());}return result;}
    @Test void signedAssignedIdentitySurvivesRepackagingButRejectsChangedMembers()throws Exception{
        var f=fixture(2);byte[] draft=archive(f.entries());UUID build=UUID.randomUUID();byte[] sealed=BootArtifact.seal(draft,build,f.signer());var artifact=BootArtifact.inspect(sealed);
        assertEquals(f.metadata().artifactId(),artifact.hash());assertEquals(build,artifact.proof().build());artifact.verify(f.signer().publicKeyEncoded(),f.pkg().canonicalSha256(),f.metadata().nativeClasspath(),f.metadata().environment().fingerprint());
        assertEquals(artifact.hash(),BootArtifact.inspect(archive(entries(sealed))).hash());
        var changed=entries(sealed);changed.put("assets/fixture/new.txt",new byte[]{1,2,3});assertThrows(IllegalStateException.class,()->BootArtifact.inspect(archive(changed)));
        assertThrows(IllegalStateException.class,()->BootArtifact.seal(sealed,UUID.randomUUID(),f.signer()));
        var other=IdentitySigner.open(root.resolve("other"));assertThrows(IllegalStateException.class,()->artifact.verify(other.publicKeyEncoded(),f.pkg().canonicalSha256(),f.metadata().nativeClasspath(),f.metadata().environment().fingerprint()));
    }
    @Test void legacyIdsUseExistingApprovedCopiesWithoutContentDerivation()throws Exception{
        var f=fixture(1);byte[] legacy=archive(f.entries());String existing="a".repeat(64);Path cache=root.resolve("cache"),mods=root.resolve("mods");Files.createDirectories(cache);Files.createDirectories(mods);
        Files.write(cache.resolve(existing+".jar"),legacy);Path installed=mods.resolve("legacy.jar");Files.write(installed,legacy);
        assertThrows(IllegalStateException.class,()->BootArtifact.inspect(legacy));assertEquals(existing,BootFiles.inspect(installed,cache).hash());
        var changed=new TreeMap<>(f.entries());changed.put("assets/fixture/changed.txt",new byte[]{1});Files.write(installed,archive(changed));assertThrows(IllegalStateException.class,()->BootFiles.inspect(installed,cache));
    }
    @Test void publicationDoesNotOverwriteDifferentContentWithTheSameResourceId()throws Exception{
        var f=fixture(2);var sealed=BootArtifact.seal(archive(f.entries()),UUID.randomUUID(),f.signer());var id=f.metadata().artifactId();Path cache=root.resolve("publish");BootFiles.publish(cache,id+".jar",sealed,id,()->true);BootFiles.publish(cache,id+".jar",sealed,id,()->true);
        var altered=new TreeMap<>(f.entries());altered.put("assets/fixture/a.txt",new byte[]{7});var replacement=BootArtifact.seal(archive(altered),UUID.randomUUID(),f.signer());
        assertThrows(IllegalStateException.class,()->BootFiles.publish(cache,id+".jar",replacement,id,()->true));assertArrayEquals(sealed,Files.readAllBytes(cache.resolve(id+".jar")));
    }
}
