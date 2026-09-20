package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Verified immutable dependency artifacts and class ownership, never initialized for inspection. */
public final class JavaDependencyArtifacts {
    private JavaDependencyArtifacts(){}
    public record Prepared(List<Path> paths,Map<String,UUID> classOwners){public Prepared{paths=List.copyOf(paths);classOwners=Map.copyOf(classOwners);}}
    public static Prepared inspect(JavaDependencyGraph graph,ContentAddressedStore content)throws Exception {
        var paths=new ArrayList<Path>();var owners=new TreeMap<String,UUID>();long total=0;int count=0;
        for(var node:graph.nodes()){
            byte[] bytes;var path=content.pathFor(node.artifact());try(var in=Files.newInputStream(path)){bytes=in.readNBytes(16*1024*1024+1);}
            total+=bytes.length;if(bytes.length>16*1024*1024||total>128L*1024*1024||!RuntimePackageCanonicalizer.sha256(bytes).equals(node.artifact()))throw new IllegalStateException("JAVA_DEPENDENCY_ARTIFACT");
            NativeCompilationSnapshot.verifyReceipt(path,node.className(),node.sourceHash(),node.nativeClasspath(),graph.embedded(node).receiptHash());
            boolean entry=false,receipt=false;int entryCount=0;var names=new HashSet<String>();
            try(var zip=new ZipInputStream(new java.io.ByteArrayInputStream(bytes))){ZipEntry file;long expanded=0;
                while((file=zip.getNextEntry())!=null){if(++entryCount>100001)throw new IllegalStateException("JAVA_DEPENDENCY_ARCHIVE");if(file.isDirectory())continue;String name=file.getName();if(!names.add(name)||name.startsWith("/")||name.contains("..")||name.contains("\\"))throw new IllegalStateException("JAVA_DEPENDENCY_ARCHIVE");
                    byte[] value=zip.readNBytes(8*1024*1024+1);expanded+=value.length;if(value.length>8*1024*1024||expanded>128L*1024*1024)throw new IllegalStateException("JAVA_DEPENDENCY_ARCHIVE");
                    if(name.equals("META-INF/mineagent/compilation.json")){
                        if(value.length>8192)throw new IllegalStateException("JAVA_DEPENDENCY_RECEIPT");var metadata=new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
                        if(!metadata.path("className").asText().equals(node.className())||!metadata.path("sourceSha256").asText().equals(node.sourceHash())||!metadata.path("nativeClasspath").asText().equals(node.nativeClasspath())||!metadata.path("javaDependencies").asText("").equals(graph.embedded(node).receiptHash()))throw new IllegalStateException("JAVA_DEPENDENCY_RECEIPT");receipt=true;
                    }
                    if(!name.endsWith(".class")||name.equals("module-info.class"))continue;
                    if(++count>100000||name.startsWith("java/")||name.startsWith("javax/")||name.startsWith("net/minecraft/")||name.startsWith("net/neoforged/")||name.startsWith("dev/mineagent/runtime/"))throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_NAMESPACE");
                    if(!name.equals(java.lang.classfile.ClassFile.of().parse(value).thisClass().asInternalName()+".class"))throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_NAME");
                    String type=name.substring(0,name.length()-6).replace('/','.');if(owners.putIfAbsent(type,node.publication())!=null)throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_AMBIGUOUS");
                    if(type.equals(node.className()))entry=true;
                }
            }
            if(!entry||!receipt)throw new IllegalStateException("JAVA_DEPENDENCY_ENTRY_MISSING");paths.add(path);
        }
        return new Prepared(paths,owners);
    }
    public static Prepared materialize(JavaDependencyGraph graph,ContentAddressedStore content,Path directory)throws Exception {
        var inspected=inspect(graph,content);Files.createDirectories(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory))throw new IllegalStateException("JAVA_DEPENDENCY_DIRECTORY");
        var paths=new ArrayList<Path>();
        for(var node:graph.nodes()){
            Path path=directory.resolve(node.artifact()+".jar");
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createLink(path,content.pathFor(node.artifact()));
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IllegalStateException("JAVA_DEPENDENCY_FILE");paths.add(path);
        }
        verify(graph,paths);return new Prepared(paths,inspected.classOwners());
    }
    public static void rejectCollisions(List<Path> jars,Set<String> owned)throws Exception {
        if(owned.isEmpty())return;
        for(var jar:jars)try(var zip=new java.util.jar.JarFile(jar.toFile(),false)){
            var entries=zip.entries();int count=0;
            while(entries.hasMoreElements()){
                var entry=entries.nextElement();if(++count>300000)throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_LIMIT");
                String name=entry.getName();if(name.endsWith(".class")&&owned.contains(name.substring(0,name.length()-6).replace('/','.')))throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_COLLISION");
            }
        }
    }
    public static void verify(JavaDependencyGraph graph,List<Path> paths)throws Exception {
        if(paths.size()!=graph.nodes().size())throw new IllegalStateException("JAVA_DEPENDENCY_ARTIFACT");
        for(int i=0;i<paths.size();i++){try(var in=Files.newInputStream(paths.get(i))){byte[] data=in.readNBytes(16*1024*1024+1);if(data.length>16*1024*1024||!RuntimePackageCanonicalizer.sha256(data).equals(graph.nodes().get(i).artifact()))throw new IllegalStateException("JAVA_DEPENDENCY_ARTIFACT");}}
    }
}
