package dev.mineagent.runtime.core.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;import java.io.*;import java.time.*;import java.util.*;

/** Immutable resource versions, addressed by assigned IDs, never by a content digest. */
public final class ResourceLibrary {
 public static final long MAX_BYTES=64L*1024*1024;
 public record Resource(UUID id,UUID world,UUID owner,long revision,String name,String mime,String source,long fetchedAt,long bytes,UUID parent,Map<String,String> selection){}
 private static final ObjectMapper JSON=new ObjectMapper();private final Path root;private final UUID world,owner;
 public ResourceLibrary(Path data,UUID world,UUID owner)throws IOException{this.world=Objects.requireNonNull(world);this.owner=Objects.requireNonNull(owner);root=data.toAbsolutePath().normalize().resolve("resources").resolve(world.toString()).resolve(owner.toString());Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("RESOURCE_ROOT_CHANGED");}
 public Resource save(String name,String mime,String source,byte[] bytes,UUID parent,Map<String,String> selection)throws IOException{
  if(name==null||name.isBlank()||name.length()>240||name.codePoints().anyMatch(Character::isISOControl)||mime==null||mime.length()>120||source==null||source.length()>4096||bytes==null||bytes.length>MAX_BYTES)throw new IllegalArgumentException("RESOURCE_INPUT_INVALID");if(parent!=null)get(parent);
  UUID id=UUID.randomUUID();Path stage=Files.createDirectory(root.resolve("pending-"+id)),target=root.resolve(id.toString());var resource=new Resource(id,world,owner,1,name,mime,source,Instant.now().toEpochMilli(),bytes.length,parent,Map.copyOf(selection));
  Files.write(stage.resolve("data"),bytes,StandardOpenOption.CREATE_NEW);Files.writeString(stage.resolve("metadata.json"),JSON.writeValueAsString(resource),StandardOpenOption.CREATE_NEW);Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);return resource;
 }
 public Resource get(UUID id)throws IOException{Path folder=root.resolve(id.toString());if(Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("RESOURCE_NOT_FOUND");var metadata=folder.resolve("metadata.json");if(!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)||Files.size(metadata)>16384)throw new IOException("RESOURCE_METADATA_INVALID");var r=JSON.readValue(Files.readString(metadata),Resource.class);if(!r.id().equals(id)||!r.world().equals(world)||!r.owner().equals(owner))throw new SecurityException("RESOURCE_SCOPE_MISMATCH");return r;}
 public Path data(UUID id)throws IOException{var r=get(id);Path file=root.resolve(id.toString()).resolve("data");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=r.bytes())throw new IOException("RESOURCE_FILE_CHANGED");return file;}
 public byte[] read(UUID id)throws IOException{var r=get(id);if(r.bytes()>MAX_BYTES)throw new IOException("RESOURCE_TOO_LARGE");return Files.readAllBytes(data(id));}
 public Map<String,Object> list(String query,int offset)throws IOException{if(query==null||query.length()>160||offset<0)throw new IllegalArgumentException("RESOURCE_QUERY");var rows=new ArrayList<Resource>();try(var entries=Files.list(root)){for(var folder:entries.filter(f->f.getFileName().toString().matches("[a-f0-9-]{36}")).toList()){var r=get(UUID.fromString(folder.getFileName().toString()));if((r.name()+" "+r.mime()+" "+r.source()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))rows.add(r);}}rows.sort(Comparator.comparingLong(Resource::fetchedAt).reversed().thenComparing(r->r.id().toString()));return Map.of("resources",rows.stream().skip(offset).limit(32).toList(),"total",rows.size(),"nextOffset",offset+32<rows.size()?offset+32:-1);}
 public static Map<String,Object> view(Resource r){var out=new LinkedHashMap<String,Object>();out.put("resource_id",r.id());out.put("revision",r.revision());out.put("name",r.name());out.put("mime",r.mime());out.put("bytes",r.bytes());out.put("source",r.source());out.put("fetchedAt",r.fetchedAt());out.put("untrustedContent",true);if(r.parent()!=null){out.put("parent",r.parent());out.put("selection",r.selection());}return out;}
}
