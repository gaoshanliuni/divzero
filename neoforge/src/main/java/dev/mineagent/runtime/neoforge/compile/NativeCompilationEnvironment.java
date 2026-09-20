package dev.mineagent.runtime.neoforge.compile;

import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.compile.NativeSourceArchive;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import java.lang.module.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

/** Captures resolved class resources plus an optional hash-locked sibling sources JAR, without loading or initializing indexed classes. */
public final class NativeCompilationEnvironment {
    public record State(String status,String snapshot,String environment,String physicalSide,int modules,long classes,long classBytes,String error){}
    public record Captured(String hash,NativeCompilationSnapshot snapshot){}
    private static final AtomicBoolean WRITING=new AtomicBoolean();
    private static volatile State state=new State("NOT_CAPTURED","","","",0,0,0,"");
    private static volatile Captured latest;
    private NativeCompilationEnvironment(){}
    public static State state(){return state;}
    public static Captured latest(){var value=latest;if(value==null)throw new IllegalStateException("NATIVE_CLASSPATH_NOT_CAPTURED");return value;}
    public static Captured capture(ContentAddressedStore content,BooleanSupplier permit)throws Exception{
        if(!WRITING.compareAndSet(false,true))throw new IllegalStateException("NATIVE_CLASSPATH_BUSY");
        try{
            if(!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CLASSPATH_AUTHORITY_CHANGED");var env=NativePackageCompatibility.observe();if(!env.namespace().equals("official"))throw new IllegalStateException("NATIVE_CLASSPATH_NAMESPACE_UNVERIFIED");String physical=net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()?"CLIENT":"SERVER";
            state=new State("CAPTURING","",env.fingerprint(),physical,0,0,0,"");ModuleLayer layer=net.neoforged.fml.loading.FMLLoader.getCurrent().getGameLayer();if(layer==null)throw new IllegalStateException("NATIVE_CLASSPATH_MODULE_LAYER_UNAVAILABLE");
            var layers=new ArrayList<ModuleLayer>();var seenLayers=Collections.newSetFromMap(new IdentityHashMap<ModuleLayer,Boolean>());collect(layer,seenLayers,layers);
            // FML startup/API modules are siblings of the game layer rather than guaranteed parents. BOOT sources
            // legitimately compile against their public APIs, so capture the exact already-loaded trusted layers.
            for(var anchor:List.<Class<?>>of(net.neoforged.fml.common.Mod.class,net.neoforged.bus.api.IEventBus.class,
                    net.neoforged.fml.ModContainer.class,net.neoforged.api.distmarker.Dist.class,
                    net.neoforged.fml.loading.FMLEnvironment.class,net.neoforged.fml.loading.FMLPaths.class,
                    net.neoforged.fml.loading.FMLLoader.class)){
                var anchorLayer=anchor.getModule().getLayer();if(anchorLayer!=null)collect(anchorLayer,seenLayers,layers);
            }
            var modules=new ArrayList<NativeCompilationSnapshot.Module>();var names=new HashSet<String>();var seenClasses=new HashMap<String,String>();long total=0,sourceBytes=0;int count=0,sourceFiles=0,ordinal=0;
            for(var current:layers){int layerNumber=ordinal++;
                for(var module:current.modules().stream().sorted(Comparator.comparing(Module::getName)).toList()){
                    String name=module.getName();if(name.startsWith("java.")||name.startsWith("jdk."))continue;if(modules.size()>=512)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");
                    var resolved=current.configuration().findModule(name).orElseThrow();var reference=resolved.reference();var output=new BoundedJarBuffer();int classes=0;
                    try(var reader=reference.open();var entries=reader.list();var jar=new JarOutputStream(output)){
                        var paths=entries.filter(p->p.endsWith(".class")&&!p.equals("module-info.class")&&!p.startsWith("META-INF/")).limit(300001).sorted().toList();
                        if(paths.size()>300000)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");
                        for(String path:paths){
                            if(path.startsWith("/")||path.contains("..")||path.contains("\\")||path.length()>1024)throw new IllegalStateException("NATIVE_CLASSPATH_ENTRY");if(++count>300000)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");
                            byte[] bytes;try(var input=reader.open(path).orElseThrow(()->new IllegalStateException("NATIVE_CLASSPATH_SOURCE_CHANGED"))){bytes=input.readNBytes(8*1024*1024+1);}total+=bytes.length;if(bytes.length>8*1024*1024||total>512L*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");
                            var parsed=java.lang.classfile.ClassFile.of().parse(bytes);if(!path.equals(parsed.thisClass().asInternalName()+".class"))throw new IllegalStateException("NATIVE_CLASSPATH_CLASS_NAME");
                            String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes),old=seenClasses.putIfAbsent(path,hash);if(old!=null&&!old.equals(hash))throw new IllegalStateException("NATIVE_CLASSPATH_AMBIGUOUS_CLASS");
                            var entry=new JarEntry(path);entry.setTime(0);jar.putNextEntry(entry);jar.write(bytes);jar.closeEntry();classes++;if(classes%512==0&&!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CLASSPATH_AUTHORITY_CHANGED");
                        }
                    }
                    if(classes==0)continue;byte[] bytes=output.toByteArray();if(bytes.length>256*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");var stored=content.put(bytes);String key=name;while(!names.add(key))key+=".layer"+layerNumber;NativeSourceArchive.Attachment sources=null;
                    try{var location=reference.location().orElse(null);if(location!=null&&location.getScheme().equalsIgnoreCase("file")&&sourceBytes<512L*1024*1024&&sourceFiles<100000)sources=NativeSourceArchive.discover(Path.of(location),content,permit,512L*1024*1024-sourceBytes,100000-sourceFiles).orElse(null);}catch(Exception sourceFailure){String code=Objects.toString(sourceFailure.getMessage(),"");if(code.equals("NATIVE_CLASSPATH_AUTHORITY_CHANGED"))throw sourceFailure;MineAgentRuntimeMod.LOGGER.warn("Native source attachment rejected module={} code={}",name,code.matches("NATIVE_SOURCE_[A-Z_]{1,80}")?code:"NATIVE_SOURCE_CAPTURE_FAILED");}
                    var capturedModule=sources==null?new NativeCompilationSnapshot.Module(key,module.getDescriptor().rawVersion().orElse(""),stored.sha256(),stored.size(),classes):new NativeCompilationSnapshot.Module(key,module.getDescriptor().rawVersion().orElse(""),stored.sha256(),stored.size(),classes,sources.sha256(),sources.size(),sources.bytes(),sources.files(),sources.kind());if(sources!=null){sourceBytes+=sources.bytes();sourceFiles+=sources.files();}
                    if(!stored.sha256().equals(dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(NativeCompilationSnapshot.read(stored.path(),256*1024*1024))))throw new IllegalStateException("NATIVE_CLASSPATH_MODULE_HASH");
                    modules.add(capturedModule);state=new State("CAPTURING","",env.fingerprint(),physical,modules.size(),count,total,"");
                }
            }
            // Public FML APIs can live in the launcher unnamed module, whose layer is null. Capture only the
            // exact code-source archives of fixed already-loaded API anchors; never enumerate arbitrary classpath.
            var anchorArchives=new TreeSet<Path>();
            for(var anchor:List.<Class<?>>of(net.neoforged.fml.common.Mod.class,net.neoforged.bus.api.IEventBus.class,
                    net.neoforged.fml.ModContainer.class,net.neoforged.api.distmarker.Dist.class,
                    net.neoforged.fml.loading.FMLEnvironment.class,net.neoforged.fml.loading.FMLPaths.class,
                    net.neoforged.fml.loading.FMLLoader.class)){
                var source=anchor.getProtectionDomain().getCodeSource();if(source==null||source.getLocation()==null||!source.getLocation().getProtocol().equalsIgnoreCase("file"))throw new IllegalStateException("NATIVE_CLASSPATH_ANCHOR_SOURCE");
                Path archive=Path.of(source.getLocation().toURI()).toRealPath();if(!Files.isRegularFile(archive,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(archive))throw new IllegalStateException("NATIVE_CLASSPATH_ANCHOR_SOURCE");anchorArchives.add(archive);
            }
            for(Path archive:anchorArchives){
                if(modules.size()>=512)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");var output=new BoundedJarBuffer();int classes=0;
                try(var input=new java.util.jar.JarFile(archive.toFile(),false);var jar=new JarOutputStream(output)){
                    var entries=input.stream().filter(e->!e.isDirectory()&&e.getName().endsWith(".class")&&!e.getName().equals("module-info.class")&&!e.getName().startsWith("META-INF/")).sorted(Comparator.comparing(JarEntry::getName)).limit(300001).toList();
                    if(entries.size()>300000)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");
                    for(var source:entries){String path=source.getName();byte[] bytes;try(var stream=input.getInputStream(source)){bytes=stream.readNBytes(8*1024*1024+1);}if(bytes.length>8*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");
                        var parsed=java.lang.classfile.ClassFile.of().parse(bytes);if(!path.equals(parsed.thisClass().asInternalName()+".class"))throw new IllegalStateException("NATIVE_CLASSPATH_CLASS_NAME");String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes),old=seenClasses.get(path);if(old!=null){if(!old.equals(hash))throw new IllegalStateException("NATIVE_CLASSPATH_AMBIGUOUS_CLASS");continue;}
                        if(++count>300000||(total+=bytes.length)>512L*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");seenClasses.put(path,hash);var entry=new JarEntry(path);entry.setTime(0);jar.putNextEntry(entry);jar.write(bytes);jar.closeEntry();classes++;if(classes%512==0&&!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CLASSPATH_AUTHORITY_CHANGED");}
                }
                if(classes==0)continue;byte[] bytes=output.toByteArray();var stored=content.put(bytes);String key="classpath."+archive.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]","_");while(!names.add(key))key+=".duplicate";modules.add(new NativeCompilationSnapshot.Module(key,"",stored.sha256(),stored.size(),classes));state=new State("CAPTURING","",env.fingerprint(),physical,modules.size(),count,total,"");
            }
            if(!seenClasses.containsKey("net/minecraft/server/MinecraftServer.class")||!seenClasses.containsKey("net/neoforged/neoforge/common/NeoForge.class")||!seenClasses.containsKey("dev/mineagent/runtime/api/packages/RuntimePackage.class")||!seenClasses.containsKey("net/neoforged/fml/common/Mod.class")||!seenClasses.containsKey("net/neoforged/bus/api/IEventBus.class")||!seenClasses.containsKey("net/neoforged/fml/ModContainer.class")||!seenClasses.containsKey("net/neoforged/api/distmarker/Dist.class")||!seenClasses.containsKey("net/neoforged/fml/loading/FMLPaths.class"))throw new IllegalStateException("NATIVE_CLASSPATH_INCOMPLETE");
            if(!permit.getAsBoolean()||!env.fingerprint().equals(NativePackageCompatibility.observe().fingerprint()))throw new IllegalStateException("NATIVE_CLASSPATH_ENVIRONMENT_CHANGED");
            var snapshot=new NativeCompilationSnapshot(1,env.namespace(),physical,"RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",env,modules,total,count,sourceBytes,sourceFiles);var stored=content.put(snapshot.encode());NativeCompilationSnapshot.read(content,stored.sha256());if(!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CLASSPATH_AUTHORITY_CHANGED");var captured=new Captured(stored.sha256(),snapshot);latest=captured;state=new State("READY",captured.hash(),env.fingerprint(),physical,modules.size(),count,total,"");return captured;
        }catch(Exception failure){String code=Objects.toString(failure.getMessage(),"");if(!code.matches("NATIVE_CLASSPATH_[A-Z_]{1,80}"))code="NATIVE_CLASSPATH_CAPTURE_FAILED";state=new State("FAILED","","","",0,0,0,code);throw new IllegalStateException(code,failure);}finally{WRITING.set(false);}
    }
    private static void collect(ModuleLayer layer,Set<ModuleLayer> seen,List<ModuleLayer> result){if(!seen.add(layer))return;result.add(layer);for(var parent:layer.parents())collect(parent,seen,result);}
    private static final class BoundedJarBuffer extends ByteArrayOutputStream {
        private void reserve(int length){if(length<0||(long)count+length>256L*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");}
        @Override public synchronized void write(int value){reserve(1);super.write(value);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){Objects.checkFromIndexSize(offset,length,bytes.length);reserve(length);super.write(bytes,offset,length);}
    }
}
