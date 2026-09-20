package dev.mineagent.runtime.neoforge.boot;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reads actual approved Mod files. Never resolves a dependency from library enabled or an uninstalled build. */
final class NativeBootDependencies {
    record Installed(BootInstallStore.Build build,BootArtifact artifact,Path file){}
    private final BootInstallStore store;private final Path mods;
    NativeBootDependencies(BootInstallStore store,Path mods){this.store=store;this.mods=mods;}
    BootDependencyGraph resolve(RuntimePackage root,BooleanSupplier permit)throws Exception {
        if(root.dependencies().isEmpty())return BootDependencyGraph.empty();
        var nodes=new LinkedHashMap<UUID,BootDependencyGraph.Node>();var visiting=new HashSet<UUID>();visiting.add(root.packageId());
        var known=store.all();for(var edge:root.dependencies().entrySet())resolve(edge.getKey(),edge.getValue(),null,nodes,visiting,known,permit,0);
        var result=new BootDependencyGraph(1,List.copyOf(nodes.values()));result.require(root);return result;
    }
    private void resolve(UUID id,String version,BootDependencyGraph.Node expected,Map<UUID,BootDependencyGraph.Node> nodes,Set<UUID> visiting,List<BootInstallStore.Build> known,BooleanSupplier permit,int depth)throws Exception {
        if(depth>BootDependencyGraph.MAX_DEPTH||visiting.contains(id))throw new IllegalStateException("BOOT_DEPENDENCY_CYCLE_OR_DEPTH");
        var old=nodes.get(id);if(old!=null){if(!old.version().equals(version)||expected!=null&&!expected.equals(old))throw new IllegalStateException("BOOT_DEPENDENCY_VERSION_CONFLICT");return;}
        if(nodes.size()>=BootDependencyGraph.MAX_NODES||!permit.getAsBoolean())throw new IllegalStateException("BOOT_DEPENDENCY_LIMIT_OR_AUTHORITY");
        Installed selected=null;var slots=new HashSet<String>();
        for(var b:known){if(b.artifact().isEmpty()||!b.manifest().packageId().equals(id)||!b.directory().equals(mods.toString())||!slots.add(BootFiles.filename(b)))continue;Path path=mods.resolve(BootFiles.filename(b));if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            var image=installed(path,known);if(!image.artifact().metadata().manifest().version().equals(version))continue;if(selected!=null)throw new IllegalStateException("BOOT_DEPENDENCY_AMBIGUOUS");selected=image;
        }
        if(selected==null)throw new IllegalStateException("BOOT_DEPENDENCY_NOT_INSTALLED");var b=selected.build();var meta=selected.artifact().metadata();
        var node=new BootDependencyGraph.Node(id,version,b.manifest().canonicalSha256(),b.artifact(),meta.modId(),BootFiles.filename(b),b.publicKey(),meta.manifest().dependencies());
        if(expected!=null&&!expected.equals(node))throw new IllegalStateException("BOOT_DEPENDENCY_TRANSITIVE_CHANGED");
        String side=net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()?"CLIENT":"SERVER";if(!meta.physicalSide().equals(side))throw new IllegalStateException("BOOT_DEPENDENCY_PHYSICAL_SIDE");
        visiting.add(id);var childGraph=meta.dependencies()==null?BootDependencyGraph.empty():meta.dependencies();
        for(var child:meta.manifest().dependencies().entrySet())resolve(child.getKey(),child.getValue(),childGraph.node(child.getKey()),nodes,visiting,known,permit,depth+1);
        visiting.remove(id);nodes.put(id,node);if(nodes.size()>BootDependencyGraph.MAX_NODES)throw new IllegalStateException("BOOT_DEPENDENCY_LIMIT_OR_AUTHORITY");
    }
    private Installed installed(Path file,List<BootInstallStore.Build> known)throws Exception {
        byte[] bytes=NativeCompilationSnapshot.read(file,BootExtensionPlan.MAX_ARCHIVE);var image=BootArtifact.inspect(bytes);
        var candidates=known.stream().filter(v->v.artifact().equals(image.hash())&&v.directory().equals(mods.toString())&&BootFiles.filename(v).equals(file.getFileName().toString())).toList();
        if(candidates.isEmpty())throw new IllegalStateException("BOOT_DEPENDENCY_UNKNOWN_FILE");
        for(var owner:candidates.stream().map(BootInstallStore.Build::owner).distinct().toList())new NativeBootUpgrades(store,mods,store.root().resolve("cache")).reconcile(owner);
        var b=candidates.stream().map(v->store.get(v.id())).filter(v->Set.of("INSTALLED_PENDING_RESTART","UPGRADE_PREDECESSOR","FILE_STATE_UNKNOWN").contains(v.phase())).findFirst().orElseThrow(()->new IllegalStateException("BOOT_DEPENDENCY_NOT_APPROVED"));
        image.verify(Base64.getDecoder().decode(b.publicKey()),b.manifest().canonicalSha256(),b.nativeClasspath(),b.environment());return new Installed(b,image,file);
    }
    void requireCurrent(RuntimePackage root,BootDependencyGraph graph,BooleanSupplier permit)throws Exception {
        var expected=graph==null?BootDependencyGraph.empty():graph;expected.require(root);var current=resolve(root,permit);
        if(!new HashSet<>(current.nodes()).equals(new HashSet<>(expected.nodes())))throw new IllegalStateException("BOOT_DEPENDENCY_CHANGED");
        var upgrades=store.upgrades();for(var n:expected.nodes())if(upgrades.stream().anyMatch(u->u.input().filename().equals(n.filename())&&!Set.of("CANCELLED","APPLIED","ROLLED_BACK").contains(u.phase())))throw new IllegalStateException("BOOT_DEPENDENCY_PENDING_CHANGE");
        if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
    }
    void requireNotUsed(UUID packageId,String nextArtifact)throws Exception {
        String targetModId="";var checked=new HashSet<String>();var known=store.all();for(var b:known){if(b.artifact().isEmpty()||!b.directory().equals(mods.toString())||!checked.add(BootFiles.filename(b)))continue;Path path=mods.resolve(BootFiles.filename(b));if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            if(b.manifest().packageId().equals(packageId)){
                var image=BootArtifact.inspect(NativeCompilationSnapshot.read(path,BootExtensionPlan.MAX_ARCHIVE));var own=known.stream().filter(v->v.manifest().packageId().equals(packageId)&&v.artifact().equals(image.hash())&&v.directory().equals(mods.toString())&&BootFiles.filename(v).equals(path.getFileName().toString())).findFirst().orElseThrow(()->new IllegalStateException("BOOT_DEPENDENCY_UNKNOWN_FILE"));
                image.verify(Base64.getDecoder().decode(own.publicKey()),own.manifest().canonicalSha256(),own.nativeClasspath(),own.environment());targetModId=image.metadata().modId();continue;
            }
            var value=installed(path,known);var graph=value.artifact().metadata().dependencies();
            if(graph!=null&&graph.nodes().stream().anyMatch(n->n.packageId().equals(packageId)&&!n.artifact().equals(nextArtifact)))throw new IllegalStateException("BOOT_DEPENDENCY_REQUIRED_BY_INSTALLED");
        }
        if(nextArtifact.isEmpty()&&!targetModId.isEmpty())for(var mod:net.neoforged.fml.ModList.get().getMods()){
            if(mod.getModId().equals(targetModId))continue;var file=net.neoforged.fml.ModList.get().getModFileById(mod.getModId());if(file==null||!Files.exists(file.getFile().getFilePath()))continue;
            for(var dependency:mod.getDependencies())if(dependency.getModId().equals(targetModId)&&dependency.getSide().isCorrectSide()&&dependency.getType()==net.neoforged.neoforgespi.language.IModInfo.DependencyType.REQUIRED)throw new IllegalStateException("BOOT_DEPENDENCY_REQUIRED_BY_MOD");
        }
    }
}
