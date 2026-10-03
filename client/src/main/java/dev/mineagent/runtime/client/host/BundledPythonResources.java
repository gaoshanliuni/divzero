package dev.mineagent.runtime.client.host;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reads only this build's classpath resources. No URLs, shell, or system Python fallback. */
final class BundledPythonResources {
    private static final String PREFIX="/META-INF/divzero/python/";
    private static final byte[] LOCK=bytes("bundle-lock.json",65536);
    private static final JsonNode MANIFEST=manifest();
    static final String ID=digest(LOCK);
    private static byte[] bytes(String path,int max){try(var in=BundledPythonResources.class.getResourceAsStream(PREFIX+path)){if(in==null)throw new IOException("PYTHON_BUNDLED_RESOURCE_MISSING");var value=in.readNBytes(max+1);if(value.length>max)throw new IOException("PYTHON_BUNDLED_RESOURCE_LIMIT");return value;}catch(IOException e){throw new IllegalStateException("PYTHON_BUNDLE_UNAVAILABLE",e);}}
    private static JsonNode manifest(){try{var n=new ObjectMapper().readTree(LOCK);if(n.path("schema").asInt()!=1||n.path("wheels").size()!=8)throw new IOException("PYTHON_BUNDLE_MANIFEST");return n;}catch(IOException e){throw new IllegalStateException("PYTHON_BUNDLE_MANIFEST",e);}}
    static String digest(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    static List<String> requirements(){var out=new ArrayList<String>();for(var w:MANIFEST.path("wheels"))out.add(w.path("name").asText()+"=="+w.path("version").asText());return List.copyOf(out);}
    static void copyRuntime(Path target,BooleanSupplier live)throws Exception{copy("runtime.tar.gz",target,MANIFEST.path("runtime"),live);}
    static Path wheels(Path root,BooleanSupplier live)throws Exception{
        Path directory=ManagedPythonRuntime.safe(root,"bundle-"+ID);Files.createDirectories(directory);
        for(var w:MANIFEST.path("wheels"))copy("wheels/"+w.path("filename").asText(),ManagedPythonRuntime.safe(directory,w.path("filename").asText()),w,live);
        Path constraints=ManagedPythonRuntime.safe(directory,"constraints.txt");String content=String.join("\n",requirements())+"\n";
        if(Files.exists(constraints,LinkOption.NOFOLLOW_LINKS)){if(!Files.readString(constraints).equals(content))throw new IOException("PYTHON_BUNDLE_CONSTRAINTS_CHANGED");}
        else Files.writeString(constraints,content,StandardOpenOption.CREATE_NEW);
        return directory;
    }
    private static void copy(String source,Path target,JsonNode expected,BooleanSupplier live)throws Exception{
        String hash=expected.path("sha256").asText();long size=expected.path("bytes").asLong();
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verify(target,hash,size);return;}
        Files.createDirectories(target.getParent());Path temporary=Files.createTempFile(target.getParent(),"bundled-python-",".partial");
        try(var in=BundledPythonResources.class.getResourceAsStream(PREFIX+source)){
            if(in==null)throw new IOException("PYTHON_BUNDLED_RESOURCE_MISSING");long total=0;
            try(var out=Files.newOutputStream(temporary)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(!live.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("HOST_CONTEXT_CHANGED");total+=n;if(total>size)throw new IOException("PYTHON_BUNDLE_INTEGRITY_FAILED");out.write(b,0,n);}}
            verify(temporary,hash,size);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void verify(Path file,String expected,long bytes)throws Exception{
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=bytes)throw new IOException("PYTHON_BUNDLE_INTEGRITY_FAILED");
        var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
        if(!HexFormat.of().formatHex(hash.digest()).equals(expected))throw new IOException("PYTHON_BUNDLE_INTEGRITY_FAILED");
    }
    private BundledPythonResources(){}
}
