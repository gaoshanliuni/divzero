package dev.mineagent.runtime.core.maintenance;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.file.*;
import java.nio.channels.*;
import java.time.Instant;
import java.util.*;

/** Offline-only entry point in the embedded Worker JAR. Never loads Minecraft or invokes a shell. */
public final class JavaMaintenance {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args){try{
        var a=arguments(args);Path game=directory(Path.of(required(a,"game")));Path log=game.resolve("mineagent-maintenance");Files.createDirectories(log);log=directory(log);
        String request=required(a,"request");UUID.fromString(request);Path result=log.resolve(request+".result.json");
        try{if(a.containsKey("wait-pid"))waitForExit(Long.parseLong(a.get("wait-pid")),Instant.parse(required(a,"wait-start")));else if(!Boolean.parseBoolean(a.getOrDefault("offline","false")))throw new IllegalArgumentException("MAINTENANCE_OFFLINE_REQUIRED");
            Map<String,Object> outcome;try(var offline=GameProcessLease.offline(game)){outcome=switch(required(a,"kind")){case "python-repair"->PythonMaintenance.repair(game,required(a,"bundle"));case "boot-upgrade"->upgrade(game,a);case "boot-recovery"->recoverBoot(game,a);default->throw new IllegalArgumentException("MAINTENANCE_KIND");};}
            atomicJson(result,outcome);System.out.println(JSON.writeValueAsString(outcome));
        }catch(Exception error){atomicJson(result,Map.of("status","FAILED","error",Objects.toString(error.getMessage(),error.getClass().getSimpleName())));throw error;}
    }catch(Exception error){System.err.println("MAINTENANCE_FAILED: "+Objects.toString(error.getMessage(),error.getClass().getSimpleName()));System.exit(1);}}
    static Map<String,String> arguments(String[] args){var out=new LinkedHashMap<String,String>();for(int i=0;i<args.length;i++){String key=args[i];if(!key.startsWith("--")||i+1==args.length||out.putIfAbsent(key.substring(2),args[++i])!=null)throw new IllegalArgumentException("MAINTENANCE_ARGUMENTS");}if(!Set.of("game","request","kind","operation","build","plan-hash","mod-id","action","mods","wait-pid","wait-start","offline","bundle","preview").containsAll(out.keySet()))throw new IllegalArgumentException("MAINTENANCE_ARGUMENTS");return out;}
    private static String required(Map<String,String> a,String key){String v=a.get(key);if(v==null||v.isBlank())throw new IllegalArgumentException("MAINTENANCE_MISSING_"+key);return v;}
    static void waitForExit(long pid,Instant started)throws Exception{
        if(pid==ProcessHandle.current().pid())throw new IllegalStateException("MAINTENANCE_CANNOT_WAIT_SELF");
        for(;;){var p=ProcessHandle.of(pid);if(p.isEmpty()||!p.get().isAlive())return;var actual=p.get().info().startInstant();if(actual.isEmpty())throw new IllegalStateException("MAINTENANCE_PROCESS_IDENTITY_UNKNOWN");if(!actual.get().equals(started))return;Thread.sleep(200);}
    }
    static Path directory(Path p)throws Exception{Path value=p.toAbsolutePath().normalize();if(!Files.isDirectory(value,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(value)||!value.toRealPath().equals(value))throw new IllegalStateException("MAINTENANCE_DIRECTORY_LINK");return value;}
    static Path child(Path parent,String name){if(name.isBlank()||name.contains("/")||name.contains("\\")||name.contains(":")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("MAINTENANCE_PATH");Path path=parent.resolve(name);if(Files.isSymbolicLink(path))throw new IllegalStateException("MAINTENANCE_FILE_LINK");return path;}
    private static byte[] checked(Path p,String hash,int limit)throws Exception{var data=NativeCompilationSnapshot.read(p,limit);if(!RuntimePackageCanonicalizer.sha256(data).equals(hash))throw new IllegalStateException("MAINTENANCE_HASH_CHANGED");return data;}
    private static void atomicJson(Path destination,Object value)throws Exception{byte[] bytes=JSON.writeValueAsBytes(value);if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("MAINTENANCE_RECEIPT_ALREADY_EXISTS");Path pending=Files.createTempFile(destination.getParent(),".maintenance-",".pending");try{Files.write(pending,bytes);try(var c=FileChannel.open(pending,StandardOpenOption.WRITE)){c.force(true);}Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(pending);}}
    public static Map<String,Object> upgrade(Path game,Map<String,String> a)throws Exception{
        Path root=directory(game.resolve("mineagent-runtime-data/boot-install")),plans=directory(root.resolve("upgrades")),cache=directory(root.resolve("cache"));
        UUID operation=UUID.fromString(required(a,"operation"));String hash=required(a,"plan-hash"),action=required(a,"action");if(!hash.matches("[a-f0-9]{64}")||!Set.of("apply","rollback").contains(action))throw new IllegalArgumentException("MAINTENANCE_PLAN_ARGUMENTS");
        try(var c=FileChannel.open(child(root,"boot-installs.lock"),StandardOpenOption.WRITE);var lease=c.tryLock()){
            if(lease==null)throw new IllegalStateException("BOOT_STORE_IN_USE");
            Path planFile=child(plans,operation+".json");var plan=JSON.readValue(checked(planFile,hash,16384),BootUpgradePlan.class);
            Path mods=directory(Path.of(required(a,"mods")));if(!plan.operation().equals(operation)||!plan.modId().equals(required(a,"mod-id"))||!mods.toString().equals(plan.directory()))throw new IllegalStateException("BOOT_UPGRADE_APPROVAL_CHANGED");
            if(Files.exists(child(plans,operation+".cancelled"),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_UPGRADE_CANCELLED");
            if(action.equals("apply")&&Files.exists(child(plans,operation+".rollback.json"),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_UPGRADE_ALREADY_ROLLED_BACK");
            String wanted=action.equals("apply")?plan.nextArtifact():plan.previousArtifact(),before=action.equals("apply")?plan.previousArtifact():plan.nextArtifact();
            byte[] bytes=checked(child(cache,wanted+".jar"),wanted,BootExtensionPlan.MAX_ARCHIVE);var artifact=BootArtifact.inspect(bytes);var m=artifact.metadata();
            if(!m.manifest().packageId().equals(plan.packageId())||!m.modId().equals(plan.modId())||!m.manifest().canonicalSha256().equals(action.equals("apply")?plan.nextCanonical():plan.previousCanonical()))throw new IllegalStateException("BOOT_UPGRADE_ARTIFACT_CHANGED");
            if(action.equals("apply")&&(m.replacement()==null||!m.replacement().build().equals(plan.previousBuild())||!m.replacement().artifact().equals(plan.previousArtifact())))throw new IllegalStateException("BOOT_UPGRADE_PREDECESSOR_CHANGED");
            dependencies(mods,artifact,plan.filename());Path target=child(mods,plan.filename()),receipt=child(plans,operation+"."+action+".json");String current=RuntimePackageCanonicalizer.sha256(NativeCompilationSnapshot.read(target,BootExtensionPlan.MAX_ARCHIVE));
            if(!current.equals(wanted)&&!current.equals(before))throw new IllegalStateException("BOOT_UPGRADE_SLOT_CHANGED");
            if(Boolean.parseBoolean(a.getOrDefault("preview","false")))return Map.of("status","PREVIEW","operation",operation,"action",action,"alreadyApplied",current.equals(wanted),"worldModified",false);
            if(Files.exists(receipt,LinkOption.NOFOLLOW_LINKS)){var old=JSON.readTree(NativeCompilationSnapshot.read(receipt,4096));if(!current.equals(wanted)||!old.path("planHash").asText().equals(hash)||!old.path("targetHash").asText().equals(wanted)||!old.path("action").asText().equals(action))throw new IllegalStateException("BOOT_UPGRADE_RECEIPT_CONFLICT");return Map.of("status","ALREADY_APPLIED","operation",operation);}
            if(!current.equals(wanted)){
                Path backup=child(mods,plan.filename()+".upgrade-"+operation+"."+action+".previous");byte[] prior=checked(target,before,BootExtensionPlan.MAX_ARCHIVE);
                if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS))checked(backup,before,BootExtensionPlan.MAX_ARCHIVE);else{Files.write(backup,prior,StandardOpenOption.CREATE_NEW);try(var b=FileChannel.open(backup,StandardOpenOption.WRITE)){b.force(true);}}
                Path staged=Files.createTempFile(mods,".mineagent-swap-",".pending");try{
                    Files.write(staged,bytes);try(var b=FileChannel.open(staged,StandardOpenOption.WRITE)){b.force(true);}checked(staged,wanted,BootExtensionPlan.MAX_ARCHIVE);
                    checked(planFile,hash,16384);if(Files.exists(child(plans,operation+".cancelled"),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_UPGRADE_CANCELLED");checked(target,before,BootExtensionPlan.MAX_ARCHIVE);dependencies(mods,artifact,plan.filename());
                    Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);checked(target,wanted,BootExtensionPlan.MAX_ARCHIVE);checked(backup,before,BootExtensionPlan.MAX_ARCHIVE);
                }finally{Files.deleteIfExists(staged);}
            }
            atomicJson(receipt,Map.of("schema",1,"operation",operation,"planHash",hash,"action",action,"targetHash",wanted,"observedAt",Instant.now().toString()));
            return Map.of("status","APPLIED","operation",operation,"action",action,"targetHash",wanted,"requiresGameRestart",true,"worldDataChanged",false);
        }
    }
    private static void dependencies(Path mods,BootArtifact artifact,String ownSlot)throws Exception{
        var m=artifact.metadata();var graph=m.dependencies()==null?BootDependencyGraph.empty():m.dependencies();graph.require(m.manifest());
        for(var n:graph.nodes()){var actual=BootArtifact.inspect(checked(child(mods,n.filename()),n.artifact(),BootExtensionPlan.MAX_ARCHIVE));n.require(actual.metadata(),actual.hash());if(!actual.metadata().physicalSide().equals(m.physicalSide()))throw new IllegalStateException("BOOT_DEPENDENCY_SIDE_CHANGED");graph.requireEmbedded(actual.metadata());}
        try(var stream=Files.newDirectoryStream(mods,"mineagent-boot-*.jar")){int count=0;for(var path:stream){if(++count>4096)throw new IllegalStateException("BOOT_DIRECTORY_LIMIT");if(path.getFileName().toString().equals(ownSlot))continue;var other=BootArtifact.inspect(NativeCompilationSnapshot.read(path,BootExtensionPlan.MAX_ARCHIVE));
            if(other.metadata().modId().equals(m.modId())||!Collections.disjoint(other.classPackages(),artifact.classPackages()))throw new IllegalStateException("BOOT_UPGRADE_MOD_CONFLICT");
            for(var n:other.metadata().dependencies()==null?List.<BootDependencyGraph.Node>of():other.metadata().dependencies().nodes())if(n.packageId().equals(m.manifest().packageId())&&!n.artifact().equals(artifact.hash()))throw new IllegalStateException("BOOT_DEPENDENCY_REQUIRED_BY_INSTALLED");
        }}
    }
    public static Map<String,Object> recoverBoot(Path game,Map<String,String> a)throws Exception{
        Path root=directory(game.resolve("mineagent-runtime-data/boot-install")),mods=directory(Path.of(required(a,"mods")));UUID id=UUID.fromString(required(a,"build"));String action=required(a,"action");
        if(!Set.of("remove","restore").contains(action))throw new IllegalArgumentException("MAINTENANCE_RECOVERY_ACTION");
        try(var c=FileChannel.open(child(root,"boot-installs.lock"),StandardOpenOption.WRITE);var lease=c.tryLock()){
            if(lease==null)throw new IllegalStateException("BOOT_STORE_IN_USE");var build=JSON.readValue(NativeCompilationSnapshot.read(child(directory(root.resolve("receipts")),id+".json"),1024*1024),BootInstallStore.Build.class);
            if(!build.id().equals(id)||!build.modId().equals(required(a,"mod-id"))||!build.directory().equals(mods.toString()))throw new IllegalStateException("BOOT_RECOVERY_APPROVAL_CHANGED");
            String slot=BootFiles.filename(build);Path jar=child(mods,slot),disabled=child(mods,slot+".disabled"),source=action.equals("remove")?jar:disabled,target=action.equals("remove")?disabled:jar;
            if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS)){checked(target,build.artifact(),BootExtensionPlan.MAX_ARCHIVE);return Map.of("status","ALREADY_APPLIED","action",action);}
            var artifact=BootArtifact.inspect(checked(source,build.artifact(),BootExtensionPlan.MAX_ARCHIVE));artifact.verify(Base64.getDecoder().decode(build.publicKey()),build.manifest().canonicalSha256(),build.nativeClasspath(),build.environment());
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_RECOVERY_TARGET_EXISTS");
            if(action.equals("restore"))dependencies(mods,artifact,slot);
            else try(var others=Files.newDirectoryStream(mods,"mineagent-boot-*.jar")){for(var path:others){if(path.equals(jar))continue;var other=BootArtifact.inspect(NativeCompilationSnapshot.read(path,BootExtensionPlan.MAX_ARCHIVE));for(var n:other.metadata().dependencies()==null?List.<BootDependencyGraph.Node>of():other.metadata().dependencies().nodes())if(n.packageId().equals(build.manifest().packageId()))throw new IllegalStateException("BOOT_DEPENDENCY_REQUIRED_BY_INSTALLED");}}
            if(Boolean.parseBoolean(a.getOrDefault("preview","false")))return Map.of("status","PREVIEW","action",action,"worldModified",false);
            checked(source,build.artifact(),BootExtensionPlan.MAX_ARCHIVE);Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);checked(target,build.artifact(),BootExtensionPlan.MAX_ARCHIVE);return Map.of("status","APPLIED","action",action,"backupPreserved",true,"requiresGameRestart",true,"worldDataChanged",false);
        }
    }
    private JavaMaintenance(){}
}
