package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable source references and exact source-set manifests. Legacy field names are opaque IDs. */
public final class CodeDraftSources {
    public static final String MANIFEST_PATH="META-INF/divzero/source-set.json";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public record SourceSet(String version,String entry,Map<String,CodeDraft.SourceRef> files,Map<UUID,String> dependencies){
        public SourceSet{dev.mineagent.runtime.api.packages.ResourceIds.require(version);files=Collections.unmodifiableMap(new TreeMap<>(files));dependencies=dependencies==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(dependencies));validate(entry,files);}
    }
    public static SourceSet set(CodeDraft draft)throws Exception{return new SourceSet(draft.sourceVersion(),draft.path(),refs(draft),draft.dependencies());}
    public static void bind(CodeDraft draft,ContentAddressedStore content)throws Exception{content.put(draft.primarySource().sha256(),utf8(draft.source()));storeSet(content,set(draft));}
    public static CodeDraft.SourceRef store(ContentAddressedStore content,String text)throws Exception{var ref=ref(text);content.put(ref.sha256(),utf8(text));return ref;}
    public static void storeSet(ContentAddressedStore content,SourceSet set)throws Exception{content.put(set.version(),JSON.writeValueAsBytes(set));}
    public static SourceSet readSet(ContentAddressedStore content,String version)throws Exception{var bytes=content.read(version);if(bytes.length>65536)throw new IllegalStateException("STUDIO_SOURCE_SET_SIZE");var value=JSON.readValue(bytes,SourceSet.class);if(!value.version().equals(version))throw new IllegalStateException("STUDIO_SOURCE_SET_ID_CHANGED");return value;}
    public static SourceSet captureRequest(ContentAddressedStore content,UUID request,String entry,Map<String,String> sources,Map<UUID,String> dependencies)throws Exception{
        String version=dev.mineagent.runtime.api.packages.ResourceIds.version(request,0,5);
        if(java.nio.file.Files.exists(content.pathFor(version))){var existing=readSet(content,version);if(!existing.entry().equals(entry)||!existing.files().keySet().equals(sources.keySet())||!existing.dependencies().equals(dependencies))throw new IllegalStateException("JAVA_SOURCE_REQUEST_REUSED");for(var file:existing.files().entrySet())if(!read(content,file.getValue()).equals(sources.get(file.getKey())))throw new IllegalStateException("JAVA_SOURCE_REQUEST_REUSED: "+file.getKey());return existing;}
        var refs=new TreeMap<String,CodeDraft.SourceRef>();int ordinal=0;for(var file:new TreeMap<>(sources).entrySet()){String id=dev.mineagent.runtime.api.packages.ResourceIds.version(request,++ordinal,6);byte[] bytes=utf8(file.getValue());var ref=new CodeDraft.SourceRef(id,bytes.length,dev.mineagent.runtime.api.packages.ResourceProof.issue(id,bytes));content.put(id,bytes);refs.put(file.getKey(),ref);}var set=new SourceSet(version,entry,refs,dependencies);storeSet(content,set);return set;
    }
    public static boolean sameReferences(Map<String,CodeDraft.SourceRef> a,Map<String,CodeDraft.SourceRef> b){return a.keySet().equals(b.keySet())&&a.entrySet().stream().allMatch(e->e.getValue().sha256().equals(b.get(e.getKey()).sha256())&&e.getValue().bytes()==b.get(e.getKey()).bytes());}
    public static void requireSet(SourceSet set,String entry,Map<String,CodeDraft.SourceRef> files,Map<UUID,String> dependencies){if(!set.entry().equals(entry)||!sameReferences(set.files(),files)||!set.dependencies().equals(dependencies))throw new IllegalStateException("STUDIO_SOURCE_SET_CHANGED");}
    public static final int MAX_FILES=64,MAX_BYTES=1024*1024,MAX_CHARS=16000;
    private CodeDraftSources(){}
    public static boolean isSource(String path){return path!=null&&path.toLowerCase(Locale.ROOT).matches(".*\\.(java|m?js)");}
    public static boolean descriptor(dev.mineagent.runtime.api.packages.RuntimeResourceRef ref){if(!ref.path().equals(MANIFEST_PATH))return false;if(ref.size()<1||ref.size()>65536||!ref.mediaType().equals("application/json"))throw new IllegalArgumentException("STUDIO_SOURCE_SET_RESOURCE");return true;}
    public static String packageVersion(dev.mineagent.runtime.api.packages.RuntimePackage pkg){var reference=pkg.resources().get(MANIFEST_PATH);return reference==null?pkg.canonicalSha256():reference.sha256();}
    public static boolean java(String path){return path!=null&&path.toLowerCase(Locale.ROOT).endsWith(".java");}
    public static void path(String value){
        if(value==null||!value.matches("[A-Za-z0-9_./-]{1,128}")||value.startsWith("/")||value.contains("..")||value.startsWith("ui/")||value.startsWith("META-INF/")||value.contains("//")
                ||!value.toLowerCase(Locale.ROOT).matches(".*\\.(java|m?js)"))throw new IllegalArgumentException("STUDIO_WORKSPACE_PATH");
    }
    public static Map<String,CodeDraft.SourceRef> refs(CodeDraft draft)throws Exception {
        var result=new TreeMap<>(draft.additionalSources());result.put(draft.path(),draft.primarySource());return Collections.unmodifiableMap(result);
    }
    public static CodeDraft.SourceRef ref(String text)throws Exception {
        if(text==null||text.length()>MAX_CHARS)throw new IllegalArgumentException("STUDIO_WORKSPACE_SOURCE_LIMIT");
        byte[] bytes=utf8(text);String id=dev.mineagent.runtime.api.packages.ResourceIds.create();return new CodeDraft.SourceRef(id,bytes.length,dev.mineagent.runtime.api.packages.ResourceProof.issue(id,bytes));
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
    public static String fingerprint(CodeDraft draft)throws Exception{return draft.sourceVersion();}
    public static String read(ContentAddressedStore content,CodeDraft.SourceRef ref)throws Exception {
        byte[] bytes;try(var in=java.nio.file.Files.newInputStream(content.pathFor(ref.sha256()))){bytes=in.readNBytes(64001);}
        if(bytes.length!=ref.bytes()||!ref.proof().isEmpty()&&!dev.mineagent.runtime.api.packages.ResourceProof.verify(ref.sha256(),bytes,ref.proof()))throw new IllegalStateException("STUDIO_WORKSPACE_SOURCE_CHANGED");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(text.length()>MAX_CHARS)throw new IllegalStateException("STUDIO_WORKSPACE_SOURCE_LIMIT");return text;
    }
    public static Map<String,String> readAll(CodeDraft draft,ContentAddressedStore content)throws Exception{
        var refs=refs(draft);if(!draft.additionalSources().isEmpty())validate(draft.path(),refs);
        var result=new TreeMap<String,String>();result.put(draft.path(),draft.source());for(var item:draft.additionalSources().entrySet())result.put(item.getKey(),read(content,item.getValue()));return Collections.unmodifiableMap(result);
    }
}
