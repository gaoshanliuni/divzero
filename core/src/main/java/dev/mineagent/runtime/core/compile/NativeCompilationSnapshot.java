package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Immutable compile-time module resources and optional sibling source attachments. Not live transforms, object state or execution authority. */
public record NativeCompilationSnapshot(int schema,String namespace,String physicalSide,String mappingStatus,
        NativeCompatibilityPolicy.Environment environment,List<Module> modules,long classBytes,long classCount,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) long sourceBytes,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) int sourceFiles){
    public record Module(String name,String version,String sha256,long size,int classes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String sourceSha256,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) long sourceSize,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) long sourceBytes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) int sourceFiles,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String sourceKind){
        public Module(String name,String version,String sha256,long size,int classes){this(name,version,sha256,size,classes,"",0,0,0,"");}
        public Module{if(name==null||!name.matches("[A-Za-z0-9_.-]{1,160}")||version==null||version.length()>128||sha256==null||!sha256.matches("[a-f0-9]{64}")||size<1||size>256L*1024*1024||classes<1||classes>300000)throw new IllegalArgumentException("NATIVE_CLASSPATH_MODULE");sourceSha256=sourceSha256==null?"":sourceSha256;sourceKind=sourceKind==null?"":sourceKind;boolean absent=sourceSha256.isEmpty();if(absent!=(sourceSize==0&&sourceBytes==0&&sourceFiles==0&&sourceKind.isEmpty())||!absent&&(!sourceSha256.matches("[a-f0-9]{64}")||sourceSize<1||sourceSize>128L*1024*1024||sourceBytes<1||sourceBytes>128L*1024*1024||sourceFiles<1||sourceFiles>100000||!sourceKind.equals("SIBLING_SOURCES_JAR")))throw new IllegalArgumentException("NATIVE_SOURCE_MODULE");}
    }
    public NativeCompilationSnapshot(int schema,String namespace,String physicalSide,String mappingStatus,NativeCompatibilityPolicy.Environment environment,List<Module> modules,long classBytes,long classCount){this(schema,namespace,physicalSide,mappingStatus,environment,modules,classBytes,classCount,0,0);}
    public NativeCompilationSnapshot{if(modules==null||environment==null||namespace==null||physicalSide==null)throw new IllegalArgumentException("NATIVE_CLASSPATH_SNAPSHOT");modules=List.copyOf(modules);if(schema!=1||!Set.of("CLIENT","SERVER").contains(physicalSide)||!namespace.equals(environment.namespace())||!Set.of("RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS","RAW_MODULE_RESOURCES_WITH_EXPLICIT_LIVE_OVERLAYS").contains(mappingStatus)||modules.isEmpty()||modules.size()>512||classBytes<1||classBytes>512L*1024*1024||classCount<1||classCount>300000||modules.stream().mapToLong(Module::classes).sum()!=classCount||sourceBytes<0||sourceBytes>512L*1024*1024||sourceFiles<0||sourceFiles>100000||modules.stream().mapToLong(Module::sourceBytes).sum()!=sourceBytes||modules.stream().mapToInt(Module::sourceFiles).sum()!=sourceFiles)throw new IllegalArgumentException("NATIVE_CLASSPATH_SNAPSHOT");if(modules.stream().map(Module::name).distinct().count()!=modules.size())throw new IllegalArgumentException("NATIVE_CLASSPATH_MODULE_DUPLICATE");}
    public static NativeCompilationSnapshot read(ContentAddressedStore content,String hash)throws Exception{if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("NATIVE_CLASSPATH_HASH");byte[] bytes=read(content.pathFor(hash),4*1024*1024);if(!RuntimePackageCanonicalizer.sha256(bytes).equals(hash))throw new IllegalStateException("NATIVE_CLASSPATH_HASH");return new ObjectMapper().readValue(bytes,NativeCompilationSnapshot.class);}
    public byte[] encode()throws Exception{return new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(this);}
    public Module module(String name){return modules.stream().filter(m->m.name().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("NATIVE_CLASSPATH_MODULE_UNKNOWN"));}
    public static byte[] read(Path file,int maximum)throws Exception{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("NATIVE_CLASSPATH_FILE");try(var in=Files.newInputStream(file)){byte[] bytes=in.readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");return bytes;}}
    public byte[] bytes(ContentAddressedStore content,Module module)throws Exception{byte[] bytes=read(content.pathFor(module.sha256()),256*1024*1024);if(bytes.length!=module.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(module.sha256()))throw new IllegalStateException("NATIVE_CLASSPATH_MODULE_HASH");return bytes;}
    public List<Path> materialize(ContentAddressedStore content,Path directory)throws Exception{
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)))throw new IllegalStateException("NATIVE_CLASSPATH_DIRECTORY");Files.createDirectories(directory);var result=new ArrayList<Path>();var classes=new HashMap<String,String>();long total=0;int count=0;
        for(var module:modules){byte[] image=bytes(content,module);Path target=directory.resolve(module.sha256()+".jar");int moduleCount=0;var entries=new HashSet<String>();
            // Parse the verified image, not a second open of a potentially changed content path.
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(image))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(entry.isDirectory()||!name.endsWith(".class")||name.equals("module-info.class")||name.startsWith("META-INF/")||name.startsWith("/")||name.contains("..")||name.contains("\\")||name.length()>1024||!entries.add(name))throw new IllegalStateException("NATIVE_CLASSPATH_ENTRY");byte[] bytes=zip.readNBytes(8*1024*1024+1);total+=bytes.length;moduleCount++;if(bytes.length>8*1024*1024||total>512L*1024*1024||++count>300000)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");if(!name.equals(java.lang.classfile.ClassFile.of().parse(bytes).thisClass().asInternalName()+".class"))throw new IllegalStateException("NATIVE_CLASSPATH_CLASS_NAME");String hash=RuntimePackageCanonicalizer.sha256(bytes),previous=classes.putIfAbsent(name,hash);if(previous!=null&&!previous.equals(hash))throw new IllegalStateException("NATIVE_CLASSPATH_AMBIGUOUS_CLASS");}}
            if(moduleCount!=module.classes())throw new IllegalStateException("NATIVE_CLASSPATH_INCOMPLETE");
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){byte[] current=read(target,256*1024*1024);if(!RuntimePackageCanonicalizer.sha256(current).equals(module.sha256()))throw new IllegalStateException("NATIVE_CLASSPATH_FILE_CHANGED");}else Files.createLink(target,content.pathFor(module.sha256()));
            result.add(target);
        }
        if(total!=classBytes||count!=classCount||!classes.containsKey("net/minecraft/server/MinecraftServer.class")||!classes.containsKey("net/neoforged/neoforge/common/NeoForge.class")||!classes.containsKey("dev/mineagent/runtime/api/packages/RuntimePackage.class"))throw new IllegalStateException("NATIVE_CLASSPATH_INCOMPLETE");verifyMaterialized(result);return List.copyOf(result);
    }
    public static void verifyReceipt(Path jar,String className,String sourceHash,String snapshot)throws Exception{
        try(var file=new JarFile(jar.toFile(),false)){var entry=file.getJarEntry("META-INF/mineagent/compilation.json");if(entry==null)throw new IllegalStateException("JAVA_CLASSPATH_RECEIPT");byte[] bytes;try(var in=file.getInputStream(entry)){bytes=in.readNBytes(8193);}if(bytes.length>8192)throw new IllegalStateException("JAVA_CLASSPATH_RECEIPT");var value=new ObjectMapper().readTree(bytes);
            if(!value.path("className").asText().equals(className)||!value.path("sourceSha256").asText().equals(sourceHash)||!value.path("nativeClasspath").asText().equals(snapshot))throw new IllegalStateException("JAVA_CLASSPATH_RECEIPT");}
    }
    public static void verifyReceipt(Path jar,String className,String sourceHash,String snapshot,String dependencies)throws Exception{
        verifyReceipt(jar,className,sourceHash,snapshot);
        try(var file=new JarFile(jar.toFile(),false);var in=file.getInputStream(file.getJarEntry("META-INF/mineagent/compilation.json"))){
            var value=new ObjectMapper().readTree(in.readNBytes(8193));
            if(!value.path("javaDependencies").asText("").equals(dependencies))throw new IllegalStateException("JAVA_DEPENDENCY_RECEIPT");
        }
    }
    public void verifyMaterialized(List<Path> paths)throws Exception{if(paths.size()!=modules.size())throw new IllegalStateException("NATIVE_CLASSPATH_PATH_COUNT");for(int i=0;i<paths.size();i++){var module=modules.get(i);byte[] image=read(paths.get(i),256*1024*1024);if(image.length!=module.size()||!RuntimePackageCanonicalizer.sha256(image).equals(module.sha256()))throw new IllegalStateException("NATIVE_CLASSPATH_SOURCE_CHANGED");}}
    public List<String> classPage(ContentAddressedStore content,String moduleName,String search,int offset,int maximum)throws Exception{
        if(search==null||search.length()>128||offset<0||offset>300000||maximum<1||maximum>32)throw new IllegalArgumentException("NATIVE_API_QUERY");byte[] image=bytes(content,module(moduleName));var values=new ArrayList<String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(image))){java.util.zip.ZipEntry entry;int scanned=0;long total=0;while((entry=zip.getNextEntry())!=null){if(++scanned>300000)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");byte[] body=zip.readNBytes(8*1024*1024+1);total+=body.length;if(body.length>8*1024*1024||total>512L*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");if(!entry.isDirectory()&&entry.getName().endsWith(".class")){String name=entry.getName().substring(0,entry.getName().length()-6).replace('/','.');if(name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))values.add(name);}}}
        if(offset>values.size())throw new IllegalArgumentException("NATIVE_API_OFFSET");return values.stream().sorted().skip(offset).limit(maximum).toList();
    }
    public byte[] classBytes(ContentAddressedStore content,String moduleName,String className)throws Exception{
        if(className==null||className.length()>512||className.isBlank()||className.contains("..")||className.startsWith(".")||className.endsWith(".")||className.contains("/")||className.contains("\\")||className.codePoints().anyMatch(c->c<=32))throw new IllegalArgumentException("NATIVE_API_CLASS");String wanted=className.replace('.','/')+".class";byte[] image=bytes(content,module(moduleName));
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(image))){java.util.zip.ZipEntry entry;int scanned=0;long total=0;while((entry=zip.getNextEntry())!=null){if(++scanned>300000)throw new IllegalStateException("NATIVE_CLASSPATH_LIMIT");byte[] bytes=zip.readNBytes(8*1024*1024+1);total+=bytes.length;if(bytes.length>8*1024*1024||total>512L*1024*1024)throw new IllegalStateException("NATIVE_API_CLASS_LIMIT");if(entry.getName().equals(wanted))return bytes;}}
        throw new IllegalStateException("NATIVE_API_CLASS_MISSING");
    }
}
