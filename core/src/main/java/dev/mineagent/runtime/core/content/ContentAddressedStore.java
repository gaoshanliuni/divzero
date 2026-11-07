package dev.mineagent.runtime.core.content;
import dev.mineagent.runtime.api.packages.ResourceIds;
import java.nio.file.*;import java.io.*;import java.util.*;
/** Immutable object storage. Historical class/field names remain readable; new keys are assigned resource IDs. */
public final class ContentAddressedStore {
 private static final long DEFAULT_MAX_OBJECT_SIZE=512L*1024*1024;private final Path root;private final long maxObjectSize;
 public ContentAddressedStore(Path root){this(root,DEFAULT_MAX_OBJECT_SIZE);}
 public ContentAddressedStore(Path root,long maxObjectSize){if(root==null||maxObjectSize<1)throw new IllegalArgumentException("invalid content store configuration");this.root=root.toAbsolutePath().normalize();this.maxObjectSize=maxObjectSize;}
 public StoredObject put(byte[] content)throws IOException{return put(ResourceIds.create(),content);}
 public StoredObject put(String id,byte[] content)throws IOException{
  ResourceIds.require(id);if(content==null||content.length>maxObjectSize)throw new IllegalArgumentException("content exceeds object limit");Path target=pathFor(id);Files.createDirectories(target.getParent());
  return ResourceFileLock.with(target.getParent().resolve(".publish.lock"),()->{
  if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(read(id),content))throw new ContentIntegrityException("immutable resource ID already contains different bytes");return new StoredObject(id,content.length,target);}
  Path temporary=Files.createTempFile(target.getParent(),".incoming-",".tmp");Files.write(temporary,content);
  try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target);}catch(FileAlreadyExistsException race){if(!Arrays.equals(read(id),content))throw new ContentIntegrityException("immutable resource ID conflict");/* Retain private staging evidence; do not destroy a conflicting file. */}
  return new StoredObject(id,content.length,target);});
 }
 public byte[] read(String id)throws IOException{Path file=pathFor(id);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maxObjectSize)throw new IOException("RESOURCE_FILE_INVALID");return Files.readAllBytes(file);}
 public Path pathFor(String id){ResourceIds.require(id);Path path=root.resolve(id.substring(0,2)).resolve(id.substring(2)).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("content path escapes store");return path;}
 /** Persistent identity of small explicit metadata (object/version/path), never of file contents. */
 public String namedKeyId(String namespace,List<String> key)throws IOException{
  return namedKeyId(namespace,key,()->null);
 }
 public String namedKeyId(String namespace,List<String> key,java.util.concurrent.Callable<String> legacy)throws IOException{
  if(namespace==null||!namespace.matches("[a-z_]{1,40}")||key==null||key.isEmpty()||key.size()>16||key.stream().anyMatch(v->v==null||v.length()>4096)||key.stream().mapToLong(String::length).sum()>8192)throw new IllegalArgumentException("RESOURCE_METADATA_KEY");
  Path folder=root.resolve(".references");Files.createDirectories(folder);if(Files.isSymbolicLink(folder))throw new IOException("RESOURCE_BINDING_LINK");Path index=folder.resolve("metadata-ids.db");
  return ResourceFileLock.with(folder.resolve("metadata-ids.lock"),()->{if(Files.isSymbolicLink(index))throw new IOException("RESOURCE_BINDING_LINK");try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+index);var schema=db.createStatement()){
   schema.execute("PRAGMA busy_timeout=5000");schema.execute("CREATE TABLE IF NOT EXISTS resource_metadata_ids(namespace TEXT NOT NULL,reference_key TEXT NOT NULL,id TEXT NOT NULL UNIQUE,PRIMARY KEY(namespace,reference_key))");String encoded=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(key);
   try(var query=db.prepareStatement("SELECT id FROM resource_metadata_ids WHERE namespace=? AND reference_key=?")){query.setString(1,namespace);query.setString(2,encoded);try(var rows=query.executeQuery()){if(rows.next())return ResourceIds.require(rows.getString(1));}}
   String prior;try{prior=legacy.call();}catch(Exception error){throw new IOException("RESOURCE_LEGACY_ID_LOOKUP",error);}String id=prior==null?ResourceIds.create():ResourceIds.require(prior);try(var insert=db.prepareStatement("INSERT INTO resource_metadata_ids VALUES(?,?,?)")){insert.setString(1,namespace);insert.setString(2,encoded);insert.setString(3,id);insert.executeUpdate();}return id;
  }catch(java.sql.SQLException error){throw new IOException("RESOURCE_METADATA_ID_STORAGE",error);}});
 }
 /** Stable named bindings refer to explicit version/resource IDs, not to file contents. */
 public String namedId(String namespace,List<String> ids)throws IOException{
  if(!namespace.matches("[a-z_]{1,40}")||ids.isEmpty()||ids.size()>8)throw new IllegalArgumentException("RESOURCE_BINDING");Path folder=root.resolve(".references").resolve(namespace);for(String id:ids)folder=folder.resolve(ResourceIds.require(id));Files.createDirectories(folder);Path marker=folder.resolve("id");
  return ResourceFileLock.with(folder.resolve(".publish.lock"),()->{
   if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)!=64)throw new IOException("RESOURCE_BINDING_INVALID");return ResourceIds.require(Files.readString(marker));}
   String id=ResourceIds.create();Path temporary=Files.createTempFile(marker.getParent(),".binding-",".tmp");Files.writeString(temporary,id);try{Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,marker);}return id;
  });
 }
}
