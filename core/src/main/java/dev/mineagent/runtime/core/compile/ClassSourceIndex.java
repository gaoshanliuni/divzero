package dev.mineagent.runtime.core.compile;
import java.nio.file.Path;import java.lang.module.ModuleReference;import java.util.*;import java.util.concurrent.Callable;
/** Keeps source locations, not copies or digests of every class. Only duplicate names require a second read. */
public final class ClassSourceIndex {
    private final Map<String,Callable<byte[]>> sources=new HashMap<>();
    public boolean containsKey(String name){return sources.containsKey(name);}
    public boolean observe(String name,byte[] bytes,Callable<byte[]> source)throws Exception{var previous=sources.putIfAbsent(name,source);if(previous==null)return true;if(!Arrays.equals(bytes,previous.call()))throw new IllegalStateException("NATIVE_CLASSPATH_AMBIGUOUS_CLASS");return false;}
    public static byte[] module(ModuleReference module,String name)throws Exception{try(var reader=module.open();var input=reader.open(name).orElseThrow()){return bounded(input);}}
    public static byte[] archive(Path path,String name)throws Exception{try(var jar=new java.util.jar.JarFile(path.toFile(),false)){var entry=jar.getJarEntry(name);if(entry==null)throw new IllegalStateException("NATIVE_CLASSPATH_SOURCE_CHANGED");try(var input=jar.getInputStream(entry)){return bounded(input);}}}
    public static byte[] resource(Class<?> type,String name)throws Exception{try(var input=type.getResourceAsStream("/"+name)){if(input==null)throw new IllegalStateException("NATIVE_CLASSPATH_SOURCE_CHANGED");return bounded(input);}}
    private static byte[] bounded(java.io.InputStream input)throws Exception{byte[] bytes=input.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IllegalStateException("NATIVE_CLASSPATH_CLASS_LIMIT");return bytes;}
}
