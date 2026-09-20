package dev.mineagent.runtime.worker.compile;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Hash-verified dependency artifacts; uses Native modules when loaded, otherwise adds approved pending JARs. */
final class BootDependencyClasspath {
    record Inputs(List<Path> additional,Map<Path,String> hashes){
        void verify()throws Exception{for(var item:hashes.entrySet())BootFiles.archive(item.getKey(),item.getValue());}
    }
    static Inputs prepare(RuntimePackage root,BootDependencyGraph graph,NativeCompilationSnapshot snapshot,ContentAddressedStore content,Path scratch,List<Path> nativePaths)throws Exception {
        graph.require(root);var additional=new ArrayList<Path>();var hashes=new LinkedHashMap<Path,String>();var packages=new HashSet<String>();
        for(var path:nativePaths)packages.addAll(packages(path));
        Path directory=BootFiles.directory(scratch.resolve("dependency-classpath"));
        for(var node:graph.nodes()){
            byte[] bytes=BootFiles.archive(content.pathFor(node.artifact()),node.artifact());var artifact=BootArtifact.inspect(bytes);node.require(artifact.metadata(),artifact.hash());
            artifact.verify(Base64.getDecoder().decode(node.publicKey()),node.canonical(),artifact.metadata().nativeClasspath(),artifact.metadata().environment().fingerprint());graph.requireEmbedded(artifact.metadata());
            if(!artifact.metadata().physicalSide().equals(snapshot.physicalSide()))throw new IllegalStateException("BOOT_DEPENDENCY_PHYSICAL_SIDE");
            if(snapshot.environment().mods().containsKey(node.modId())){
                if(!snapshot.environment().mods().get(node.modId()).equals(node.version()))throw new IllegalStateException("BOOT_DEPENDENCY_LOADED_VERSION");snapshot.module(node.modId());
            }else{
                Path path=directory.resolve(node.artifact()+".jar");if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))BootFiles.archive(path,node.artifact());else Files.createLink(path,content.pathFor(node.artifact()));
                for(String name:artifact.classPackages())if(!packages.add(name))throw new IllegalStateException("BOOT_DEPENDENCY_PACKAGE_CONFLICT");
                additional.add(path);hashes.put(path,node.artifact());
            }
        }
        var result=new Inputs(List.copyOf(additional),Map.copyOf(hashes));result.verify();return result;
    }
    private static Set<String> packages(Path path)throws Exception {var result=new HashSet<String>();try(var jar=new JarFile(path.toFile(),false)){var entries=jar.entries();while(entries.hasMoreElements()){String name=entries.nextElement().getName();int slash=name.lastIndexOf('/');if(slash>=0&&name.endsWith(".class"))result.add(name.substring(0,slash));}}return result;}
}
