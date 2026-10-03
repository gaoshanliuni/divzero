package dev.mineagent.runtime.core.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.packages.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaMaintenanceTest {
    @TempDir Path game;
    private final ObjectMapper json=new ObjectMapper();private final UUID pkg=UUID.randomUUID(),oldBuild=UUID.randomUUID();
    private final String modId="offline_fixture";
    private byte[] artifact(String canonical,BootReplacement replacement)throws Exception{
        var resources=new HashMap<String,RuntimeResourceRef>();for(String path:List.of("boot/extension.json","boot/src/example/Main.java"))resources.put(path,new RuntimeResourceRef(path,"0".repeat(64),RuntimeResourceSide.SERVER,path.endsWith("java")?"text/x-java-source":"application/json",1));
        var compat=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of())));
        var manifest=new RuntimePackage(pkg,RuntimePackageType.EXTENSION,"Fixture","1.0",ActivationMode.BOOT_EXTENSION,Map.of(),Set.of("RUN_CODE"),Map.of("boot",new RuntimeEntrypoint("boot/extension.json",RuntimeResourceSide.SERVER,"0".repeat(64))),Map.of(),resources,PackageOrigin.GENERATED,false,1,canonical,"fixture",1,compat);
        String bootstrap="dev.mineagent.boot.p"+pkg.toString().replace("-","")+".Bootstrap";
        var metadata=new BootArtifact.Metadata(1,manifest,modId,"example.Main",bootstrap,"0".repeat(64),new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"fixture",Map.of()),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",replacement);
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(String cls:List.of("example.Main",bootstrap)){zip.putNextEntry(new ZipEntry(cls.replace('.','/')+".class"));zip.write(java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of(cls),b->b.withFlags(java.lang.reflect.AccessFlag.PUBLIC)));zip.closeEntry();}zip.putNextEntry(new ZipEntry(BootArtifact.METADATA));zip.write(json.writeValueAsBytes(metadata));zip.closeEntry();zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));zip.write("fixture".getBytes());zip.closeEntry();}return bytes.toByteArray();
    }
    private record Fixture(Path mods,Path root,Path plans,Path target,byte[] oldBytes,byte[] nextBytes,BootUpgradePlan plan,Map<String,String> args){}
    private Fixture fixture()throws Exception{
        Path mods=Files.createDirectories(game.resolve("mods")).toRealPath(),root=Files.createDirectories(game.resolve("mineagent-runtime-data/boot-install")),plans=Files.createDirectories(root.resolve("upgrades")),cache=Files.createDirectories(root.resolve("cache"));Files.writeString(root.resolve("boot-installs.lock"),"");
        byte[] before=artifact("1".repeat(64),null);String oldHash=RuntimePackageCanonicalizer.sha256(before);byte[] after=artifact("2".repeat(64),new BootReplacement(oldBuild,pkg,"1".repeat(64),oldHash,modId,""));String newHash=RuntimePackageCanonicalizer.sha256(after);String slot="mineagent-boot-"+pkg+"-"+oldHash+".jar";Path target=mods.resolve(slot);Files.write(target,before);Files.write(cache.resolve(oldHash+".jar"),before);Files.write(cache.resolve(newHash+".jar"),after);
        var plan=new BootUpgradePlan(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),pkg,oldBuild,UUID.randomUUID(),mods.toString(),slot,modId,oldHash,newHash,"1".repeat(64),"2".repeat(64),"INSTALLED_PENDING_RESTART","BUILT",1,true);Files.write(plans.resolve(plan.operation()+".json"),plan.encode());
        return new Fixture(mods,root,plans,target,before,after,plan,Map.of("mods",mods.toString(),"operation",plan.operation().toString(),"plan-hash",plan.sha256(),"mod-id",modId,"action","apply"));
    }
    @Test void exactOfflineSwapReadbackBackupAndRollback()throws Exception{
        var f=fixture();var preview=new HashMap<>(f.args);preview.put("preview","true");assertEquals("PREVIEW",JavaMaintenance.upgrade(game,preview).get("status"));assertArrayEquals(f.oldBytes,Files.readAllBytes(f.target));
        assertEquals("APPLIED",JavaMaintenance.upgrade(game,f.args).get("status"));assertArrayEquals(f.nextBytes,Files.readAllBytes(f.target));assertArrayEquals(f.oldBytes,Files.readAllBytes(f.mods.resolve(f.target.getFileName()+".upgrade-"+f.plan.operation()+".apply.previous")));
        assertEquals("ALREADY_APPLIED",JavaMaintenance.upgrade(game,f.args).get("status"));var rollback=new HashMap<>(f.args);rollback.put("action","rollback");assertEquals("APPLIED",JavaMaintenance.upgrade(game,rollback).get("status"));assertArrayEquals(f.oldBytes,Files.readAllBytes(f.target));assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));
    }
    @Test void runningLeaseCancellationAndChangedWorldFilesAreNotOverwritten()throws Exception{
        var f=fixture();try(var c=FileChannel.open(f.root.resolve("boot-installs.lock"),StandardOpenOption.WRITE);var lock=c.lock()){assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));}assertArrayEquals(f.oldBytes,Files.readAllBytes(f.target));
        Files.writeString(f.plans.resolve(f.plan.operation()+".cancelled"),f.plan.sha256());assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));Files.delete(f.plans.resolve(f.plan.operation()+".cancelled"));
        Files.writeString(f.target,"someone else's later content");assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));assertEquals("someone else's later content",Files.readString(f.target));
    }
    @Test void repairKeepsScriptsAndHistoryAndNeverFollowsPointerOutsideRoot()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(dev.mineagent.runtime.core.hostsupport.PythonEdition.bundled());Path root=Files.createDirectories(game.resolve("mineagent-host"));Files.createDirectories(root.resolve("workspace"));Files.writeString(root.resolve("workspace/player.py"),"preserved");String bundle="a".repeat(64);Files.writeString(root.resolve("environment-"+bundle+".json"),"{\"directory\":\"../world\",\"bundleId\":\""+bundle+"\"}");
        assertEquals("REPAIR_STAGED_FOR_NEXT_USE",PythonMaintenance.repair(game,bundle).get("status"));assertEquals("preserved",Files.readString(root.resolve("workspace/player.py")));assertFalse(Files.exists(root.resolve("environment-"+bundle+".json")));
    }
}
