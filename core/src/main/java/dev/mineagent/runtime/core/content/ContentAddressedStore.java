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
  if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(read(id),content))throw new ContentIntegrityException("immutable resource ID already contains different bytes");return new StoredObject(id,content.length,target);}
  Path temporary=Files.createTempFile(target.getParent(),".incoming-",".tmp");Files.write(temporary,content);
  try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target);}catch(FileAlreadyExistsException race){if(!Arrays.equals(read(id),content))throw new ContentIntegrityException("immutable resource ID conflict");/* Retain private staging evidence; do not destroy a conflicting file. */}
  return new StoredObject(id,content.length,target);
 }
 public byte[] read(String id)throws IOException{Path file=pathFor(id);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maxObjectSize)throw new IOException("RESOURCE_FILE_INVALID");return Files.readAllBytes(file);}
 public Path pathFor(String id){ResourceIds.require(id);Path path=root.resolve(id.substring(0,2)).resolve(id.substring(2)).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("content path escapes store");return path;}
 /** Stable named bindings refer to explicit version/resource IDs, not to file contents. */
 public String namedId(String namespace,List<String> ids)throws IOException{if(!namespace.matches("[a-z_]{1,40}")||ids.isEmpty()||ids.size()>8)throw new IllegalArgumentException("RESOURCE_BINDING");Path folder=root.resolve(".references").resolve(namespace);for(String id:ids)folder=folder.resolve(ResourceIds.require(id));Files.createDirectories(folder);Path marker=folder.resolve("id");if(Files.exists(marker))return ResourceIds.require(Files.readString(marker));String id=ResourceIds.create();try{Files.writeString(marker,id,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException raced){return ResourceIds.require(Files.readString(marker));}return id;}
}
