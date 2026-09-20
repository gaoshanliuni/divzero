package dev.mineagent.runtime.worker.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import javax.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;
import java.util.jar.*;
import java.io.*;

/** Builds an actual javafml Mod archive. Does not load its classes or run annotation processors. */
public final class BootExtensionCompiler {
    public record Result(boolean success,String artifact,String nativeClasspath,String modId,List<String> diagnostics){}
    public Result build(RuntimePackage pkg,String snapshotHash,ContentAddressedStore content,Path scratch)throws Exception{return build(pkg,snapshotHash,content,scratch,null);}
    public Result build(RuntimePackage pkg,String snapshotHash,ContentAddressedStore content,Path scratch,BootReplacement replacement)throws Exception{return build(pkg,snapshotHash,content,scratch,replacement,BootDependencyGraph.empty());}
    public Result build(RuntimePackage pkg,String snapshotHash,ContentAddressedStore content,Path scratch,BootReplacement replacement,BootDependencyGraph graph)throws Exception {
        var plan=BootExtensionPlan.read(pkg,content);var snapshot=NativeCompilationSnapshot.read(content,snapshotHash);plan.requireEnvironment(snapshot,replacement);
        var allPaths=snapshot.materialize(content,scratch.resolve("native-classpath"));
        var selectedPaths=replacement==null?allPaths:replacement.compilerPaths(snapshot,allPaths);
        var dependencyInputs=BootDependencyClasspath.prepare(pkg,graph,snapshot,content,scratch,selectedPaths);var nativePaths=new ArrayList<>(selectedPaths);nativePaths.addAll(dependencyInputs.additional());
        String bootstrap="dev.mineagent.boot.p"+pkg.packageId().toString().replace("-","")+".Bootstrap";
        var sources=new TreeMap<>(plan.sources());sources.put(bootstrap.replace('.','/')+".java",wrapper(plan,bootstrap));
        var compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("BOOT_JDK_REQUIRED");
        Files.createDirectories(scratch);Path output=Files.createTempDirectory(scratch,"boot-");var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            var cp=new ArrayList<>(nativePaths);var original=manager.getLocationAsPaths(StandardLocation.CLASS_PATH);if(original!=null)original.forEach(cp::add);
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH,cp);manager.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of());
            var units=new ArrayList<JavaFileObject>();sources.forEach((name,body)->units.add(new Source(name,body)));
            boolean success=Boolean.TRUE.equals(compiler.getTask(null,manager,diagnostics,List.of("--release","25","-encoding","UTF-8","-proc:none","-d",output.toString(),"-Xlint:all"),null,units).call());
            var messages=new ArrayList<>(diagnostics.getDiagnostics().stream().limit(diagnostics.getDiagnostics().size()>32?31:32).map(d->{String message=d.getKind()+" "+(d.getSource()==null?"":d.getSource().getName())+":"+d.getLineNumber()+":"+d.getColumnNumber()+" "+d.getMessage(Locale.SIMPLIFIED_CHINESE);return message.substring(0,Math.min(1500,message.length()));}).toList());if(diagnostics.getDiagnostics().size()>32)messages.add("DIAGNOSTICS_TRUNCATED: total="+diagnostics.getDiagnostics().size()+", retained=31");
            snapshot.verifyMaterialized(allPaths);dependencyInputs.verify();if(!success)return new Result(false,"",snapshotHash,plan.modId(),messages);
            var nativePackages=new HashSet<String>();for(var path:nativePaths)try(var archive=new JarFile(path.toFile(),false)){var enumeration=archive.entries();while(enumeration.hasMoreElements()){String name=enumeration.nextElement().getName();int slash=name.lastIndexOf('/');if(name.endsWith(".class")&&slash>=0)nativePackages.add(name.substring(0,slash));}}
            var entries=new TreeMap<String,byte[]>();long total=0;
            try(var walk=Files.walk(output)){
                var classes=walk.filter(Files::isRegularFile).limit(4097).toList();if(classes.size()>4096)throw new IllegalStateException("BOOT_CLASS_LIMIT");
                for(var path:classes){String name=output.relativize(path).toString().replace('\\','/');byte[] bytes=NativeCompilationSnapshot.read(path,8*1024*1024);total+=bytes.length;
                    if(!name.endsWith(".class")||total>16*1024*1024)throw new IllegalStateException("BOOT_CLASS_LIMIT");
                    var model=java.lang.classfile.ClassFile.of().parse(bytes);if(!name.equals(model.thisClass().asInternalName()+".class"))throw new IllegalStateException("BOOT_CLASS_PATH");
                    if(!name.equals(bootstrap.replace('.','/')+".class")&&(BootExtensionPlan.forbiddenClass(model.thisClass().asInternalName())||new String(bytes,StandardCharsets.ISO_8859_1).contains("Lnet/neoforged/fml/common/Mod;")))throw new IllegalStateException("BOOT_EXTRA_MOD_ENTRY");
                    String binary=model.thisClass().asInternalName();int slash=binary.lastIndexOf('/');if(slash>=0&&nativePackages.contains(binary.substring(0,slash)))throw new IllegalStateException("BOOT_NATIVE_PACKAGE_CONFLICT");
                    entries.put(name,bytes);
                }
            }
            for(var e:plan.resources().entrySet()){total+=e.getValue().length;if(total>20*1024*1024||entries.putIfAbsent(e.getKey(),e.getValue())!=null)throw new IllegalStateException("BOOT_RESOURCE_LIMIT");}
            entries.put("META-INF/neoforge.mods.toml",metadata(plan,snapshot,graph).getBytes(StandardCharsets.UTF_8));
            var receipt=new BootArtifact.Metadata(1,pkg,plan.modId(),plan.entrypoint(),bootstrap,snapshotHash,snapshot.environment(),snapshot.physicalSide(),snapshot.mappingStatus(),replacement,graph.nodes().isEmpty()?null:graph);
            entries.put(BootArtifact.METADATA,new ObjectMapper().writeValueAsBytes(receipt));
            var buffer=new ByteArrayOutputStream();try(var jar=new JarOutputStream(buffer)){
                for(var e:entries.entrySet()){var entry=new JarEntry(e.getKey());entry.setTime(0);jar.putNextEntry(entry);jar.write(e.getValue());jar.closeEntry();if(buffer.size()>BootExtensionPlan.MAX_ARCHIVE)throw new IllegalStateException("BOOT_ARCHIVE_LIMIT");}
            }
            byte[] bytes=buffer.toByteArray();BootArtifact.inspect(bytes);snapshot.verifyMaterialized(allPaths);dependencyInputs.verify();var stored=content.put(bytes);
            if(!RuntimePackageCanonicalizer.sha256(NativeCompilationSnapshot.read(stored.path(),BootExtensionPlan.MAX_ARCHIVE)).equals(stored.sha256()))throw new IllegalStateException("BOOT_ARTIFACT_HASH");
            return new Result(true,stored.sha256(),snapshotHash,plan.modId(),messages);
        }finally{try(var paths=Files.walk(output)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static String wrapper(BootExtensionPlan plan,String name){
        int dot=name.lastIndexOf('.');var pkg=plan.manifest();String dist=switch(plan.side()){case CLIENT->"net.neoforged.api.distmarker.Dist.CLIENT";case SERVER->"net.neoforged.api.distmarker.Dist.DEDICATED_SERVER";case COMMON->"{net.neoforged.api.distmarker.Dist.CLIENT,net.neoforged.api.distmarker.Dist.DEDICATED_SERVER}";};
        return "package "+name.substring(0,dot)+";\n@net.neoforged.fml.common.Mod(value=\""+plan.modId()+"\",dist="+dist+")\npublic final class Bootstrap { public Bootstrap(net.neoforged.bus.api.IEventBus bus,net.neoforged.fml.ModContainer container)throws Exception {\n"+
            "dev.mineagent.runtime.neoforge.boot.NativeBootProof.requireDependencies(Bootstrap.class,container.getModId(),\""+pkg.packageId()+"\",\""+pkg.canonicalSha256()+"\");\n"+
            "dev.mineagent.runtime.api.extension.BootExtension extension=new "+plan.entrypoint()+"();\n"+
            "extension.initialize(java.util.Map.of(\"modEventBus\",bus,\"modContainer\",container,\"physicalSide\",net.neoforged.fml.loading.FMLEnvironment.getDist().name(),\"modId\",\""+plan.modId()+"\",\"packageId\",\""+pkg.packageId()+"\",\"packageHash\",\""+pkg.canonicalSha256()+"\"));\n"+
            "dev.mineagent.runtime.neoforge.boot.NativeBootProof.initialized(Bootstrap.class,container.getModId(),\""+pkg.packageId()+"\",\""+pkg.canonicalSha256()+"\");\n}}\n";
    }
    private static String metadata(BootExtensionPlan plan,NativeCompilationSnapshot snapshot,BootDependencyGraph graph){
        String id=plan.modId();var b=new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"All Rights Reserved\"\n[[mods]]\nmodId=").append(quote(id)).append("\nversion=").append(quote(plan.manifest().version())).append("\ndisplayName=").append(quote(plan.manifest().name())).append("\ndescription=\"Explicitly approved MineAgent BOOT_EXTENSION\"\n");
        for(String file:plan.mixins())b.append("[[mixins]]\nconfig=").append(quote(file)).append('\n');
        var target=plan.manifest().nativeCompatibility().targets().get(snapshot.physicalSide());var mods=new TreeMap<>(target.requiredMods());mods.put("minecraft",snapshot.environment().minecraft());mods.put("neoforge",snapshot.environment().loaderVersion());String runtime=snapshot.environment().mods().get("mineagent_runtime");if(runtime==null)throw new IllegalStateException("BOOT_RUNTIME_DEPENDENCY");mods.put("mineagent_runtime",runtime);
        for(var node:graph.nodes()){String old=mods.putIfAbsent(node.modId(),node.version());if(old!=null&&!old.equals(node.version()))throw new IllegalStateException("BOOT_DEPENDENCY_VERSION_CONFLICT");}
        for(var mod:mods.entrySet()){if(mod.getKey().equals(id))throw new IllegalStateException("BOOT_SELF_DEPENDENCY");b.append("[[dependencies.").append(id).append("]]\nmodId=").append(quote(mod.getKey())).append("\ntype=\"required\"\nversionRange=").append(quote("["+mod.getValue()+"]")).append("\nordering=\"AFTER\"\nside=\"BOTH\"\n");}
        return b.toString();
    }
    private static String quote(String value){try{return new ObjectMapper().writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("BOOT_METADATA",e);}}
    private static final class Source extends SimpleJavaFileObject {
        private final String body;Source(String path,String body){super(URI.create("string:///"+path),Kind.SOURCE);this.body=body;}
        @Override public CharSequence getCharContent(boolean ignore){return body;}
    }
}
