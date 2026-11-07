package dev.mineagent.runtime.core.content;
import com.fasterxml.jackson.databind.ObjectMapper;import java.nio.file.*;import java.nio.channels.FileChannel;import java.nio.charset.StandardCharsets;import java.util.*;import java.io.*;
/** User-approved ONE-TIME migration of old namespace identifiers. Never accepts or hashes file contents. */
public final class LegacyNamespaceIds {
 public enum Kind { DIALOGUE_MEMORY, BLOCK_TEXTURE_SCOPE, UI_STATE_SCOPE }
 private record Mapping(String id,boolean migrated){}
 private static final ObjectMapper JSON=new ObjectMapper();private static final Map<Path,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
 public static String resolve(Path indexRoot,Kind kind,String originalScope,java.util.function.Predicate<String> legacyExists)throws IOException{
  if(originalScope==null||originalScope.length()>8192||originalScope.codePoints().anyMatch(c->c==0))throw new IllegalArgumentException("LEGACY_NAMESPACE_SCOPE");Path root=indexRoot.toAbsolutePath().normalize();Files.createDirectories(root);Path file=root.resolve("namespace-map-v2.json"),lock=root.resolve("namespace-map-v2.lock");String key=kind.name()+":"+originalScope;
  synchronized(LOCKS.computeIfAbsent(file,p->new Object())){try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lease=channel.lock()){
   if(Files.isSymbolicLink(file)||Files.isSymbolicLink(lock))throw new IOException("NAMESPACE_INDEX_LINK");var records=new TreeMap<String,Mapping>();if(Files.isRegularFile(file)){if(Files.size(file)>16*1024*1024)throw new IOException("NAMESPACE_INDEX_BUDGET");records.putAll(JSON.readValue(Files.readString(file),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Mapping>>(){}));}var existing=records.get(key);if(existing!=null)return existing.id();
   String legacy=legacy(kind,originalScope);boolean found=legacyExists.test(legacy);String id=found?legacy:kind==Kind.DIALOGUE_MEMORY?"dialogue_memory_v2_"+UUID.randomUUID():"scope-"+UUID.randomUUID();records.put(key,new Mapping(id,found));Path temporary=Files.createTempFile(root,"namespace-map-",".tmp");Files.writeString(temporary,JSON.writeValueAsString(records));try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}return id;
  }}
 }
 private static String legacy(Kind kind,String scope)throws IOException{byte[] identifier=scope.getBytes(StandardCharsets.UTF_8);if(kind==Kind.DIALOGUE_MEMORY)return "dialogue_memory_"+UUID.nameUUIDFromBytes(identifier);try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identifier));}catch(java.security.NoSuchAlgorithmException e){throw new IOException("LEGACY_NAMESPACE_MIGRATION_UNAVAILABLE",e);}}
 private LegacyNamespaceIds(){}
}
