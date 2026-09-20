package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Content-addressed source set; single-source fingerprints retain the old exact source SHA contract. */
public final class CodeDraftSources {
    public static final int MAX_FILES=64,MAX_BYTES=1024*1024,MAX_CHARS=16000;
    private CodeDraftSources(){}
    public static boolean java(String path){return path!=null&&path.toLowerCase(Locale.ROOT).endsWith(".java");}
    public static void path(String value){
        if(value==null||!value.matches("[A-Za-z0-9_./-]{1,128}")||value.startsWith("/")||value.contains("..")||value.startsWith("ui/")||value.startsWith("META-INF/")||value.contains("//")
                ||!value.toLowerCase(Locale.ROOT).matches(".*\\.(java|m?js)"))throw new IllegalArgumentException("STUDIO_WORKSPACE_PATH");
    }
    public static Map<String,CodeDraft.SourceRef> refs(CodeDraft draft)throws Exception {
        var result=new TreeMap<>(draft.additionalSources());result.put(draft.path(),ref(draft.source()));return Collections.unmodifiableMap(result);
    }
    public static CodeDraft.SourceRef ref(String text)throws Exception {
        if(text==null||text.length()>MAX_CHARS)throw new IllegalArgumentException("STUDIO_WORKSPACE_SOURCE_LIMIT");
        byte[] bytes=utf8(text);return new CodeDraft.SourceRef(RuntimePackageCanonicalizer.sha256(bytes),bytes.length);
    }
    public static byte[] utf8(String value)throws Exception{
        var buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
    }
    public static void validate(String entry,Map<String,CodeDraft.SourceRef> files){
        if(files.isEmpty()||files.size()>MAX_FILES||!files.containsKey(entry))throw new IllegalArgumentException("STUDIO_WORKSPACE_FILES");
        long bytes=0;var names=new HashSet<String>();
        for(var item:files.entrySet()){path(item.getKey());if(java(entry)!=java(item.getKey()))throw new IllegalArgumentException("STUDIO_WORKSPACE_LANGUAGE");if(!names.add(item.getKey().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("STUDIO_WORKSPACE_CASE_COLLISION");bytes+=item.getValue().bytes();}
        if(bytes>MAX_BYTES)throw new IllegalArgumentException("STUDIO_WORKSPACE_SIZE");
    }
    public static String fingerprint(CodeDraft draft)throws Exception{return studioFingerprint(draft.path(),refs(draft),draft.dependencies());}
    public static String fingerprint(String entry,Map<String,CodeDraft.SourceRef> files)throws Exception {
        return fingerprint(entry,files,Map.of());
    }
    public static String fingerprint(String entry,Map<String,CodeDraft.SourceRef> files,Map<java.util.UUID,String> dependencies)throws Exception {
        if(files.size()==1&&files.containsKey(entry)&&dependencies.isEmpty())return files.get(entry).sha256();
        var values=new TreeMap<String,String>();files.forEach((name,ref)->values.put(name,ref.sha256()));
        var data=new TreeMap<String,Object>();data.put("entry",entry);data.put("files",values);if(!dependencies.isEmpty()){var deps=new TreeMap<String,String>();dependencies.forEach((id,value)->deps.put(id.toString(),value));data.put("dependencies",deps);}return RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(data));
    }
    public static String studioFingerprint(String entry,Map<String,CodeDraft.SourceRef> files,Map<java.util.UUID,String> dependencies)throws Exception {
        String source=fingerprint(entry,files,dependencies);if(!entry.startsWith("client/"))return source;
        return RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.of("schema",1,"targetSide","CLIENT","source",source)));
    }
    public static String read(ContentAddressedStore content,CodeDraft.SourceRef ref)throws Exception {
        byte[] bytes;try(var in=java.nio.file.Files.newInputStream(content.pathFor(ref.sha256()))){bytes=in.readNBytes(64001);}
        if(bytes.length!=ref.bytes()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw new IllegalStateException("STUDIO_WORKSPACE_SOURCE_CHANGED");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(text.length()>MAX_CHARS)throw new IllegalStateException("STUDIO_WORKSPACE_SOURCE_LIMIT");return text;
    }
    public static Map<String,String> readAll(CodeDraft draft,ContentAddressedStore content)throws Exception{
        var refs=refs(draft);if(!draft.additionalSources().isEmpty())validate(draft.path(),refs);
        var result=new TreeMap<String,String>();result.put(draft.path(),draft.source());for(var item:draft.additionalSources().entrySet())result.put(item.getKey(),read(content,item.getValue()));return Collections.unmodifiableMap(result);
    }
}
