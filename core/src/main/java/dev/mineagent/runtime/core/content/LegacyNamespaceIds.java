package dev.mineagent.runtime.core.content;
import com.fasterxml.jackson.databind.ObjectMapper;import java.nio.file.*;import java.nio.channels.FileChannel;import java.nio.charset.StandardCharsets;import java.util.*;import java.io.*;
/** User-approved ONE-TIME migration of old namespace identifiers. Never accepts or hashes file contents. */
public final class LegacyNamespaceIds {
 public enum Kind { DIALOGUE_MEMORY, BLOCK_TEXTURE_SCOPE, UI_STATE_SCOPE }
 /** Used only under AssignedTaskIds' durable migration transaction when old request records exist. */
 public static UUID taskStartIdForMigration(UUID world,UUID owner,UUID operation){Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(operation);return UUID.nameUUIDFromBytes(("task-start|"+world+"|"+owner+"|"+operation).getBytes(StandardCharsets.UTF_8));}
 /** Called only after ConversationStore has checked its durable one-time migration record. */
 public static UUID nativeConversationForMigration(UUID world,UUID owner,UUID agent){Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);return UUID.nameUUIDFromBytes(("native-mention-v1|"+world+"|"+owner+"|"+agent).getBytes(StandardCharsets.UTF_8));}
 /** Called only when legacy version-3 draft IDs exist and no durable operation mapping has been saved. */
 public static UUID codeDraftForMigration(UUID world,UUID owner,UUID operation){Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(operation);return UUID.nameUUIDFromBytes(("web-studio|"+world+"|"+owner+"|"+operation).getBytes(StandardCharsets.UTF_8));}
 public static String worldPathLockForMigration(String path)throws java.security.GeneralSecurityException{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));}
 public static UUID pendingActivationForMigration(String path,String anchorIds){return UUID.nameUUIDFromBytes((path+"|"+anchorIds).getBytes(StandardCharsets.UTF_8));}
 public static UUID agentCreationForMigration(UUID world,UUID owner,UUID operation){return UUID.nameUUIDFromBytes(("agent-create|"+world+"|"+owner+"|"+operation).getBytes(StandardCharsets.UTF_8));}
 /** Only invoked while creating a durable scope mapping, and only if legacy state rows exist. */
 public static String packageUiStateForMigration(String server,UUID viewer,UUID pkg,String entry,String target)throws Exception{
  byte[] identity=new ObjectMapper().writeValueAsBytes(List.of(server,viewer.toString(),pkg.toString(),entry,target));
  return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity));
 }
 /** Used only while atomically migrating a legacy world-action row lacking its explicit operation list. */
 public static UUID worldActionForMigration(UUID batch,int index){if(batch==null||index<0||index>=256)throw new IllegalArgumentException("LEGACY_WORLD_ACTION_ID");return UUID.nameUUIDFromBytes((batch+"|"+index).getBytes(StandardCharsets.UTF_8));}
 private record Mapping(String id,boolean migrated){}
 /** Only used under the shared store's durable identity migration transaction. */
 public static UUID sharedIdentityForMigration(UUID world,String kind){
  String value=switch(kind){case "stream"->"shared-state-stream-v1|"+world;case "expiry_actor"->world+"|shared-system-expiry-v1";default->throw new IllegalArgumentException("LEGACY_SHARED_ID_KIND");};
  return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
 }
 private static final ObjectMapper JSON=new ObjectMapper();private static final Map<Path,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
 public static String resolve(Path indexRoot,Kind kind,String originalScope,java.util.function.Predicate<String> legacyExists)throws IOException{
  return resolve(indexRoot,kind,originalScope,true,legacyExists);
 }
 public static String resolve(Path indexRoot,Kind kind,String originalScope,boolean legacyCandidates,java.util.function.Predicate<String> legacyExists)throws IOException{
  if(originalScope==null||originalScope.length()>8192||originalScope.codePoints().anyMatch(c->c==0))throw new IllegalArgumentException("LEGACY_NAMESPACE_SCOPE");Path root=indexRoot.toAbsolutePath().normalize();Files.createDirectories(root);Path file=root.resolve("namespace-map-v2.json"),lock=root.resolve("namespace-map-v2.lock");String key=kind.name()+":"+originalScope;
  synchronized(LOCKS.computeIfAbsent(file,p->new Object())){try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lease=channel.lock()){
   if(Files.isSymbolicLink(file)||Files.isSymbolicLink(lock))throw new IOException("NAMESPACE_INDEX_LINK");var records=new TreeMap<String,Mapping>();if(Files.isRegularFile(file)){if(Files.size(file)>16*1024*1024)throw new IOException("NAMESPACE_INDEX_BUDGET");records.putAll(JSON.readValue(Files.readString(file),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Mapping>>(){}));}var existing=records.get(key);if(existing!=null)return checked(kind,existing.id());
   String legacy=legacyCandidates?legacy(kind,originalScope):null;boolean found=legacy!=null&&legacyExists.test(legacy);String id=found?legacy:kind==Kind.DIALOGUE_MEMORY?"dialogue_memory_v2_"+UUID.randomUUID():"scope-"+UUID.randomUUID();records.put(key,new Mapping(id,found));Path temporary=Files.createTempFile(root,"namespace-map-",".tmp");Files.writeString(temporary,JSON.writeValueAsString(records));try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}return id;
  }}
 }
 public static boolean legacyFiles(Path root,boolean directory)throws IOException{if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return false;try(var files=Files.list(root)){return files.anyMatch(p->p.getFileName().toString().matches(directory?"[a-f0-9]{64}":"[a-f0-9]{64}\\.json")&&(directory?Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS):Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)));}}
 private static String checked(Kind kind,String id)throws IOException{if(id==null||!id.matches(kind==Kind.DIALOGUE_MEMORY?"dialogue_memory_(?:v2_)?[a-f0-9-]{36}":"(?:[a-f0-9]{64}|scope-[a-f0-9-]{36})"))throw new IOException("NAMESPACE_INDEX_ID_INVALID");return id;}
 private static String legacy(Kind kind,String scope)throws IOException{byte[] identifier=scope.getBytes(StandardCharsets.UTF_8);if(kind==Kind.DIALOGUE_MEMORY)return "dialogue_memory_"+UUID.nameUUIDFromBytes(identifier);try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identifier));}catch(java.security.NoSuchAlgorithmException e){throw new IOException("LEGACY_NAMESPACE_MIGRATION_UNAVAILABLE",e);}}
 private LegacyNamespaceIds(){}
}
