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
    @org.junit.jupiter.api.BeforeEach void resolveWindowsTemporaryAlias()throws Exception{game=game.toRealPath();}
    private final ObjectMapper json=new ObjectMapper();private final UUID pkg=UUID.randomUUID(),oldBuild=UUID.randomUUID();
    private final String modId="offline_fixture";
    private record Image(byte[] bytes,RuntimePackage manifest,BootArtifact.Metadata metadata,String id){}
    private Image artifact(int schema,UUID build,String version,BootReplacement replacement,dev.mineagent.runtime.core.crypto.IdentitySigner signer)throws Exception{
        var resources=new LinkedHashMap<String,RuntimeResourceRef>();var source=new HashMap<String,byte[]>();
        for(String path:List.of("boot/extension.json","boot/src/example/Main.java")){
            String id=ResourceIds.create();byte[] bytes=(path+version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            resources.put(path,new RuntimeResourceRef(path,id,RuntimeResourceSide.SERVER,"text/plain",bytes.length));source.put(id,bytes);
        }
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"fixture",Map.of());
        var compat=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target(env.minecraft(),env.loader(),env.loaderVersion(),env.namespace(),25,Map.of())));
        var descriptor=resources.get("boot/extension.json");
        var draft=new RuntimePackage(pkg,RuntimePackageType.EXTENSION,"Fixture",version,ActivationMode.BOOT_EXTENSION,Map.of(),Set.of("RUN_CODE"),Map.of("boot",new RuntimeEntrypoint(descriptor.path(),descriptor.side(),descriptor.sha256())),Map.of(),resources,PackageOrigin.GENERATED,false,1,"0".repeat(64),"",1,compat);
        var manifest=PackageSignatures.signNew(draft,source::get,signer);String id=ResourceIds.create();
        String bootstrap="dev.mineagent.boot.p"+pkg.toString().replace("-","")+".Bootstrap";
        var metadata=new BootArtifact.Metadata(schema,manifest,modId,"example.Main",bootstrap,ResourceIds.create(),env,"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",null,replacement,schema==2?id:"");
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){
            for(String cls:List.of("example.Main",bootstrap)){zip.putNextEntry(new ZipEntry(cls.replace('.','/')+".class"));zip.write(java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of(cls),b->b.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC)));zip.closeEntry();}
            zip.putNextEntry(new ZipEntry(BootArtifact.METADATA));zip.write(json.writeValueAsBytes(metadata));zip.closeEntry();zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));zip.write("fixture".getBytes());zip.closeEntry();
        }
        return new Image(schema==2?BootArtifact.seal(bytes.toByteArray(),build,signer):bytes.toByteArray(),manifest,metadata,id);
    }
    private record Fixture(Path mods,Path root,Path plans,Path target,byte[] oldBytes,byte[] nextBytes,BootUpgradePlan plan,Map<String,String> args){}
    private Fixture fixture()throws Exception{return fixture(2);}
    private Fixture fixture(int schema)throws Exception{
        Path mods=Files.createDirectories(game.resolve("mods")).toRealPath(),root=Files.createDirectories(game.resolve("mineagent-runtime-data/boot-install")),plans=Files.createDirectories(root.resolve("upgrades")),cache=Files.createDirectories(root.resolve("cache")),receipts=Files.createDirectories(root.resolve("receipts"));
        var signer=dev.mineagent.runtime.core.crypto.IdentitySigner.open(game.resolve("identity"));String key=Base64.getEncoder().encodeToString(signer.publicKeyEncoded());UUID nextId=UUID.randomUUID(),world=UUID.randomUUID(),owner=UUID.randomUUID();
        var before=artifact(schema,oldBuild,"1.0",null,signer);var after=artifact(2,nextId,"2.0",new BootReplacement(oldBuild,pkg,before.manifest().canonicalSha256(),before.id(),modId,""),signer);
        String slot="mineagent-boot-"+pkg+"-"+before.id()+".jar";Path target=mods.resolve(slot);Files.write(target,before.bytes());Files.write(cache.resolve(before.id()+".jar"),before.bytes());Files.write(cache.resolve(after.id()+".jar"),after.bytes());
        BootUpgradePlan plan;
        try(var store=new BootInstallStore(root)){
            assertTrue(store.find(oldBuild).isEmpty());store.begin(oldBuild,world,owner,before.manifest(),key,before.metadata().environment().fingerprint(),mods.toString());
            store.built(oldBuild,before.metadata().nativeClasspath(),before.id(),modId,List.of(),before.bytes().length);var old=store.cached(oldBuild);UUID install=UUID.randomUUID();
            store.beginChange(new BootInstallStore.Change(install,oldBuild,world,owner,"INSTALL",old.revision(),"PREPARED",""));old=store.finishChange(install,true,"");
            store.begin(nextId,world,owner,after.manifest(),key,after.metadata().environment().fingerprint(),mods.toString(),oldBuild);
            store.built(nextId,after.metadata().nativeClasspath(),after.id(),modId,List.of(),after.bytes().length);var next=store.cached(nextId);
            Files.write(receipts.resolve(oldBuild+".json"),json.writeValueAsBytes(old));Files.write(receipts.resolve(nextId+".json"),json.writeValueAsBytes(next));
            plan=new BootUpgradePlan(schema,UUID.randomUUID(),world,owner,pkg,oldBuild,nextId,mods.toString(),slot,modId,before.id(),after.id(),before.manifest().canonicalSha256(),after.manifest().canonicalSha256(),old.phase(),next.phase(),1,true);
            store.stageUpgrade(plan,old.revision(),next.revision());store.upgradeStatus(plan.operation(),"WAIT_OFFLINE","");
        }
        Files.write(plans.resolve(plan.operation()+".json"),plan.encode());
        return new Fixture(mods,root,plans,target,before.bytes(),after.bytes(),plan,Map.of("mods",mods.toString(),"operation",plan.operation().toString(),"plan-hash",schema==1?ResourceIds.create():plan.sha256(),"mod-id",modId,"action","apply"));
    }
    @Test void exactOfflineSwapReadbackBackupAndRollback()throws Exception{
        var f=fixture();var preview=new HashMap<>(f.args);preview.put("preview","true");assertEquals("PREVIEW",JavaMaintenance.upgrade(game,preview).get("status"));assertArrayEquals(f.oldBytes,Files.readAllBytes(f.target));
        assertEquals("APPLIED",JavaMaintenance.upgrade(game,f.args).get("status"));assertArrayEquals(f.nextBytes,Files.readAllBytes(f.target));assertArrayEquals(f.oldBytes,Files.readAllBytes(f.mods.resolve(f.target.getFileName()+".upgrade-"+f.plan.operation()+".apply.previous")));
        assertEquals("ALREADY_APPLIED",JavaMaintenance.upgrade(game,f.args).get("status"));var rollback=new HashMap<>(f.args);rollback.put("action","rollback");assertEquals("APPLIED",JavaMaintenance.upgrade(game,rollback).get("status"));assertArrayEquals(f.oldBytes,Files.readAllBytes(f.target));assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));
    }
    @Test void runningLeaseCancellationAndChangedWorldFilesAreNotOverwritten()throws Exception{
        var f=fixture();try(var c=FileChannel.open(f.root.resolve("boot-installs.lock"),StandardOpenOption.WRITE);var lock=c.lock()){assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));}assertArrayEquals(f.oldBytes,Files.readAllBytes(f.target));
        Files.writeString(f.plans.resolve(f.plan.operation()+".cancelled"),f.plan.sha256());assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));Files.move(f.plans.resolve(f.plan.operation()+".cancelled"),f.plans.resolve(f.plan.operation()+".cancelled.saved"));
        Files.writeString(f.target,"someone else's later content");assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args));assertEquals("someone else's later content",Files.readString(f.target));
    }
    @Test void legacyApprovedPlanUsesStoredIdentityAndExactArchiveCopies()throws Exception{
        var f=fixture(1);assertEquals("APPLIED",JavaMaintenance.upgrade(game,f.args()).get("status"));assertArrayEquals(f.nextBytes(),Files.readAllBytes(f.target()));
        var rollback=new HashMap<>(f.args());rollback.put("action","rollback");assertEquals("APPLIED",JavaMaintenance.upgrade(game,rollback).get("status"));assertArrayEquals(f.oldBytes(),Files.readAllBytes(f.target()));
    }
    @Test void changedPlanAndCancelledDatabaseApprovalCannotApply()throws Exception{
        var f=fixture();Files.writeString(f.plans().resolve(f.plan().operation()+".json"),"{}");assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args()));assertArrayEquals(f.oldBytes(),Files.readAllBytes(f.target()));
        Files.write(f.plans().resolve(f.plan().operation()+".json"),f.plan().encode());
        try(var store=new BootInstallStore(f.root())){store.upgradeStatus(f.plan().operation(),"CANCELLED","");}
        assertThrows(Exception.class,()->JavaMaintenance.upgrade(game,f.args()));assertArrayEquals(f.oldBytes(),Files.readAllBytes(f.target()));
    }
    @Test void recoveryMovesExactApprovedArchiveAndRestoresItWithoutLosingBackup()throws Exception{
        var f=fixture();var args=new HashMap<>(Map.of("mods",f.mods().toString(),"build",oldBuild.toString(),"mod-id",modId,"action","remove"));
        assertEquals("APPLIED",JavaMaintenance.recoverBoot(game,args).get("status"));assertFalse(Files.exists(f.target()));assertArrayEquals(f.oldBytes(),Files.readAllBytes(f.target().resolveSibling(f.target().getFileName()+".disabled")));
        args.put("action","restore");assertEquals("APPLIED",JavaMaintenance.recoverBoot(game,args).get("status"));assertArrayEquals(f.oldBytes(),Files.readAllBytes(f.target()));
    }
    @Test void repairKeepsScriptsAndHistoryAndNeverFollowsPointerOutsideRoot()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(dev.mineagent.runtime.core.hostsupport.PythonEdition.bundled());Path root=Files.createDirectories(PythonMaintenance.runtimeRoot(game));Files.writeString(root.resolve("game.owner"),game.toRealPath().toString());Path user=Files.createDirectories(game.resolve("mineagent-host/workspace"));Files.writeString(user.resolve("player.py"),"preserved");String bundle="a".repeat(64);Files.writeString(root.resolve("environment-"+bundle+".json"),"{\"directory\":\"../world\",\"bundleId\":\""+bundle+"\"}");
        Files.writeString(root.resolve("ready-"+bundle+".json"),"{}");assertEquals("REPAIR_STAGED_FOR_NEXT_USE",PythonMaintenance.repair(game,bundle).get("status"));assertFalse(Files.exists(root.resolve("ready-"+bundle+".json")));assertEquals("preserved",Files.readString(user.resolve("player.py")));assertFalse(Files.exists(root.resolve("environment-"+bundle+".json")));
    }
}
