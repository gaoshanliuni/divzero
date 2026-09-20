package dev.mineagent.runtime.neoforge.boot;

import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Plan files only while the game is running. The separate offline helper performs the atomic slot swap. */
final class NativeBootUpgrades {
    private final BootInstallStore store;private final Path mods,cache,plans;
    NativeBootUpgrades(BootInstallStore store,Path mods,Path cache){this.store=store;this.mods=mods;this.cache=cache;plans=store.root().resolve("upgrades");}
    private Path planFile(BootUpgradePlan plan){return plans.resolve(plan.operation()+".json");}
    private Path cancelled(BootUpgradePlan plan){return plans.resolve(plan.operation()+".cancelled");}
    void publish(BootInstallStore.Upgrade upgrade,BooleanSupplier permit)throws Exception {
        var p=upgrade.input();var previous=store.get(p.previousBuild());var next=store.get(p.nextBuild());
        if(!mods.toString().equals(p.directory()))throw new IllegalStateException("BOOT_UPGRADE_DIRECTORY");
        byte[] oldBytes=BootFiles.archive(mods.resolve(p.filename()),p.previousArtifact());var before=BootArtifact.inspect(oldBytes);before.verify(Base64.getDecoder().decode(previous.publicKey()),p.previousCanonical(),previous.nativeClasspath(),previous.environment());
        var after=BootArtifact.inspect(BootFiles.archive(cache.resolve(p.nextArtifact()+".jar"),p.nextArtifact()));after.verify(Base64.getDecoder().decode(next.publicKey()),p.nextCanonical(),next.nativeClasspath(),next.environment());
        if(after.metadata().replacement()==null||!after.metadata().replacement().build().equals(previous.id())||!after.metadata().modId().equals(p.modId()))throw new IllegalStateException("BOOT_UPGRADE_PROVENANCE");
        new NativeBootDependencies(store,mods).requireCurrent(next.manifest(),after.metadata().dependencies(),permit);
        if(!dependencyIssues(after.metadata()).isEmpty())throw new IllegalStateException("BOOT_UPGRADE_DEPENDENCY_CONFLICT");
        noOtherConflict(p.filename(),after);BootFiles.publish(cache,p.previousArtifact()+".jar",oldBytes,p.previousArtifact(),permit);
        if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");write(planFile(p),p.encode());store.upgradeStatus(p.operation(),"WAIT_OFFLINE","");
    }
    void cancel(BootInstallStore.Upgrade u,BooleanSupplier permit)throws Exception {
        if(!Set.of("PREPARING","WAIT_OFFLINE","PLAN_WRITE_FAILED","CANCEL_FAILED","CANCELLED").contains(u.phase()))throw new IllegalStateException("BOOT_UPGRADE_ALREADY_APPLIED");
        var p=u.input();Path target=mods.resolve(p.filename());String observed=Files.exists(target,LinkOption.NOFOLLOW_LINKS)?RuntimePackageCanonicalizer.sha256(NativeCompilationSnapshot.read(target,BootExtensionPlan.MAX_ARCHIVE)):"";if(observed.equals(p.nextArtifact())||receipt(p,"apply")&&!receipt(p,"rollback"))throw new IllegalStateException("BOOT_UPGRADE_ALREADY_APPLIED");if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
        write(cancelled(p),p.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));store.upgradeStatus(p.operation(),"CANCELLED","",observed);
    }
    void reconcile(UUID owner)throws Exception {
        var hashes=new HashMap<String,String>();
        for(var u:store.upgrades()){
            var p=u.input();if(!p.owner().equals(owner)||!p.directory().equals(mods.toString())||Set.of("CANCELLED","ROLLED_BACK").contains(u.phase()))continue;
            boolean cancellation=cancelledMatches(p),rollback=receipt(p,"rollback");
            if(u.phase().equals("APPLIED")&&!cancellation&&!rollback)continue;
            String hash=hashes.get(p.filename());
            if(hash==null){Path target=mods.resolve(p.filename());if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))hash="";else try{hash=RuntimePackageCanonicalizer.sha256(NativeCompilationSnapshot.read(target,BootExtensionPlan.MAX_ARCHIVE));}catch(Exception unavailable){continue;}hashes.put(p.filename(),hash);}
            if(cancellation&&!hash.equals(p.nextArtifact()))store.upgradeStatus(p.operation(),"CANCELLED","",hash);
            else if(rollback)store.upgradeStatus(p.operation(),"ROLLED_BACK","",hash);
            else if(hash.equals(p.nextArtifact())&&approved(p)&&!Files.exists(cancelled(p),LinkOption.NOFOLLOW_LINKS))store.upgradeStatus(p.operation(),"APPLIED","",hash);
        }
    }
    List<Map<String,Object>> page(UUID owner,int offset)throws Exception {
        var all=store.upgrades().reversed().stream().filter(u->u.input().owner().equals(owner)).toList();if(offset<0||offset>all.size())throw new IllegalArgumentException("BOOT_OFFSET");var out=new ArrayList<Map<String,Object>>();
        for(var u:all.stream().skip(offset).limit(8).toList()){var p=u.input();out.add(Map.ofEntries(Map.entry("operation",p.operation()),Map.entry("phase",u.phase()),Map.entry("error",u.error()),Map.entry("packageId",p.packageId()),Map.entry("modId",p.modId()),Map.entry("previousBuild",p.previousBuild()),Map.entry("nextBuild",p.nextBuild()),Map.entry("previousArtifact",p.previousArtifact()),Map.entry("nextArtifact",p.nextArtifact()),Map.entry("filename",p.filename()),Map.entry("planHash",p.sha256()),Map.entry("approvedFile",approved(p)),Map.entry("cancelledFile",cancelledMatches(p)),Map.entry("applyReceipt",receipt(p,"apply")),Map.entry("rollbackReceipt",receipt(p,"rollback"))));}
        return List.copyOf(out);
    }
    int count(UUID owner)throws Exception{return (int)store.upgrades().stream().filter(u->u.input().owner().equals(owner)).count();}
    private boolean approved(BootUpgradePlan p){try{return RuntimePackageCanonicalizer.sha256(NativeCompilationSnapshot.read(planFile(p),16384)).equals(p.sha256());}catch(Exception e){return false;}}
    private boolean cancelledMatches(BootUpgradePlan p){try{return new String(NativeCompilationSnapshot.read(cancelled(p),64),java.nio.charset.StandardCharsets.US_ASCII).equals(p.sha256());}catch(Exception e){return false;}}
    private boolean receipt(BootUpgradePlan p,String action){try{var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(NativeCompilationSnapshot.read(plans.resolve(p.operation()+"."+action+".json"),4096));return json.path("operation").asText().equals(p.operation().toString())&&json.path("planHash").asText().equals(p.sha256())&&json.path("action").asText().equals(action)&&json.path("targetHash").asText().equals(action.equals("apply")?p.nextArtifact():p.previousArtifact());}catch(Exception e){return false;}}
    private static List<String> dependencyIssues(BootArtifact.Metadata next){
        var result=new ArrayList<String>();var version=new org.apache.maven.artifact.versioning.DefaultArtifactVersion(next.manifest().version());
        for(var mod:net.neoforged.fml.ModList.get().getMods())if(!mod.getModId().equals(next.modId())&&net.neoforged.fml.ModList.get().getModFileById(mod.getModId())!=null&&Files.exists(net.neoforged.fml.ModList.get().getModFileById(mod.getModId()).getFile().getFilePath()))for(var dependency:mod.getDependencies()){
            if(!dependency.getModId().equals(next.modId())||!dependency.getSide().isCorrectSide())continue;
            boolean contains=dependency.getVersionRange().containsVersion(version);boolean incompatible=switch(dependency.getType()){case REQUIRED,OPTIONAL->!contains;case INCOMPATIBLE->contains;default->false;};
            if(incompatible){String message=mod.getModId()+" / "+dependency.getType()+" / "+dependency.getVersionRange();result.add(message.substring(0,Math.min(256,message.length())));}
        }return List.copyOf(result);
    }
    void noOtherConflict(String ownSlot,BootArtifact candidate)throws Exception {
        new NativeBootDependencies(store,mods).requireNotUsed(candidate.metadata().manifest().packageId(),candidate.hash());
        var known=store.all();var checked=new HashSet<String>();for(var other:known){if(other.artifact().isEmpty())continue;String slot=BootFiles.filename(other);if(slot.equals(ownSlot)||!checked.add(slot))continue;Path file=mods.resolve(slot);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))continue;
            var image=BootArtifact.inspect(NativeCompilationSnapshot.read(file,BootExtensionPlan.MAX_ARCHIVE));var owner=known.stream().filter(b->!b.artifact().isEmpty()&&BootFiles.filename(b).equals(slot)&&b.artifact().equals(image.hash())).findFirst().orElseThrow(()->new IllegalStateException("BOOT_MANAGED_FILE_CHANGED"));image.verify(Base64.getDecoder().decode(owner.publicKey()),owner.manifest().canonicalSha256(),owner.nativeClasspath(),owner.environment());
            var contract=image.metadata().manifest().nativeCompatibility().targets().get(candidate.metadata().physicalSide());String required=contract==null?null:contract.requiredMods().get(candidate.metadata().modId());if(required!=null&&!required.equals(candidate.metadata().manifest().version()))throw new IllegalStateException("BOOT_UPGRADE_PENDING_DEPENDENCY_CONFLICT");
            if(image.metadata().modId().equals(candidate.metadata().modId())||!Collections.disjoint(image.classPackages(),candidate.classPackages()))throw new IllegalStateException("BOOT_PENDING_MOD_CONFLICT");
        }
    }
    private static void write(Path target,byte[] bytes)throws Exception {
        Path root=BootFiles.directory(target.getParent());if(!target.toAbsolutePath().normalize().getParent().equals(root))throw new IllegalStateException("BOOT_UPGRADE_DIRECTORY");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(NativeCompilationSnapshot.read(target,16384),bytes))throw new IllegalStateException("BOOT_UPGRADE_PLAN_CHANGED");return;}
        Path temporary=Files.createTempFile(root,".plan-",".pending");try{Files.write(temporary,bytes);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.createLink(target,temporary);}finally{Files.deleteIfExists(temporary);}
    }
}
