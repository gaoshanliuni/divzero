package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;

import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Signed CLIENT Java sources; download and compilation remain distinct from local execution consent. */
public record ClientJavaPlan(RuntimePackage manifest,String entrypoint,Map<String,RuntimeResourceRef> sources,long bytes){
    public static final int MAX_BUNDLE=4*1024*1024,MAX_SOURCE_BYTES=1024*1024,MAX_FILES=64;
    public static final String PREFIX="mineagent-client-java-";
    @FunctionalInterface public interface Verifier{boolean verify(byte[] value,byte[] signature)throws Exception;}
    public record Resolved(ClientJavaPlan plan,byte[] bundle,String bundleHash,Map<String,String> sources){public Resolved{bundle=bundle.clone();sources=Map.copyOf(sources);}@Override public byte[] bundle(){return bundle.clone();}}
    public ClientJavaPlan{sources=Collections.unmodifiableMap(new TreeMap<>(sources));}
    public static boolean path(String path){return path!=null&&path.startsWith("client/")&&path.endsWith(".java");}
    public static ClientJavaPlan inspect(RuntimePackage p){
        if(p.activationMode()!=ActivationMode.HOT_RUNTIME)throw new IllegalStateException("CLIENT_JAVA_HOT_RUNTIME_REQUIRED");var entry=p.entrypoints().get("client_java");if(entry==null||entry.side()==RuntimeResourceSide.SERVER||!path(entry.path()))throw new IllegalStateException("CLIENT_JAVA_ENTRY_REQUIRED");if(p.dependencies().size()>32)throw new IllegalStateException("CLIENT_JAVA_DEPENDENCY_LIMIT");if(p.nativeCompatibility()==null||!p.nativeCompatibility().targets().containsKey("CLIENT"))throw new IllegalStateException("CLIENT_JAVA_NATIVE_CONTRACT_REQUIRED");
        var sources=new TreeMap<String,RuntimeResourceRef>();long bytes=0;for(var ref:p.resources().values())if(path(ref.path())){if(ref.side()==RuntimeResourceSide.SERVER||ref.size()<1||ref.size()>256*1024||sources.put(ref.path(),ref)!=null)throw new IllegalStateException("CLIENT_JAVA_RESOURCE_INVALID");bytes+=ref.size();}var source=sources.get(entry.path());if(source==null||!source.sha256().equals(entry.sha256()))throw new IllegalStateException("CLIENT_JAVA_ENTRY_CHANGED");if(sources.isEmpty()||sources.size()>MAX_FILES||bytes>MAX_SOURCE_BYTES)throw new IllegalStateException("CLIENT_JAVA_RESOURCE_LIMIT");return new ClientJavaPlan(p,entry.path(),sources,bytes);
    }
    public byte[] bundle(ContentAddressedStore store)throws Exception{var files=new TreeMap<String,String>();for(var e:sources.entrySet()){byte[] value=store.read(e.getValue().sha256());check(e.getValue(),value);files.put(e.getKey(),Base64.getEncoder().encodeToString(value));}byte[] body=new ObjectMapper().writeValueAsBytes(new ClientScriptPlan.Bundle(manifest,files));if(body.length>MAX_BUNDLE)throw new IllegalStateException("CLIENT_JAVA_BUNDLE_LIMIT");return body;}
    public static Resolved decode(byte[] body,UUID id,long revision,String canonical,Verifier verifier)throws Exception{
        if(body==null||body.length<1||body.length>MAX_BUNDLE)throw new IllegalStateException("CLIENT_JAVA_BUNDLE_LIMIT");var bundle=new ObjectMapper().readValue(body,ClientScriptPlan.Bundle.class);var p=bundle.manifest();if(!p.packageId().equals(id)||p.revision()!=revision||!p.canonicalSha256().equals(canonical))throw new IllegalStateException("CLIENT_JAVA_SOURCE_CHANGED");if(!RuntimePackageCanonicalizer.sha256(p).equals(p.canonicalSha256())||!verifier.verify(p.canonicalSha256().getBytes(StandardCharsets.US_ASCII),Base64.getDecoder().decode(p.signature())))throw new IllegalStateException("CLIENT_JAVA_SIGNATURE");var plan=inspect(p);if(!bundle.files().keySet().equals(plan.sources().keySet()))throw new IllegalStateException("CLIENT_JAVA_RESOURCE_SET");var sources=new TreeMap<String,String>();for(var e:plan.sources().entrySet()){String encoded=bundle.files().get(e.getKey());if(encoded==null||encoded.length()>2*MAX_SOURCE_BYTES)throw new IllegalStateException("CLIENT_JAVA_BUNDLE_LIMIT");byte[] value=Base64.getDecoder().decode(encoded);check(e.getValue(),value);sources.put(e.getKey(),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString());}return new Resolved(plan,body,RuntimePackageCanonicalizer.sha256(body),sources);
    }
    public String filename(String publisher){if(!publisher.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("CLIENT_JAVA_IDENTITY");return PREFIX+publisher+"-"+manifest.packageId()+"-"+manifest.canonicalSha256()+".bin";}
    public static boolean managedName(String name){return name!=null&&name.matches("mineagent-client-java-[a-f0-9]{64}-[a-f0-9-]{36}-[a-f0-9]{64}\\.bin");}
    public static byte[] read(Path file)throws Exception{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("CLIENT_JAVA_FILE_LINK");try(var in=Files.newInputStream(file)){byte[] bytes=in.readNBytes(MAX_BUNDLE+1);if(bytes.length>MAX_BUNDLE)throw new IllegalStateException("CLIENT_JAVA_BUNDLE_LIMIT");return bytes;}}
    public static void publish(Path directory,String filename,byte[] bytes,String expected)throws Exception{if(!managedName(filename)||!RuntimePackageCanonicalizer.sha256(bytes).equals(expected))throw new IllegalStateException("CLIENT_JAVA_BUNDLE_HASH");if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)))throw new IllegalStateException("CLIENT_JAVA_CACHE_LINK");Files.createDirectories(directory);Path root=directory.toRealPath(),target=root.resolve(filename);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!RuntimePackageCanonicalizer.sha256(read(target)).equals(expected))throw new IllegalStateException("CLIENT_JAVA_CACHE_CONFLICT");return;}Path temporary=Files.createTempFile(root,".mineagent-client-java-",".pending");try{Files.write(temporary,bytes);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.createLink(target,temporary);}finally{Files.deleteIfExists(temporary);}}
    private static void check(RuntimeResourceRef ref,byte[] value)throws Exception{if(value.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(value).equals(ref.sha256()))throw new IllegalStateException("CLIENT_JAVA_RESOURCE_HASH");}
}
