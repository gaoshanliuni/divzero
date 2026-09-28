package dev.mineagent.runtime.client.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Scoped image cache; immutable hash-named PNG blobs and a CAS manifest, never a vanilla asset edit. */
public final class BlockTextureStore {
    public record Texture(String sha256,String sourceUrl,int size){public Texture{if(sha256==null||!sha256.matches("[a-f0-9]{64}")||sourceUrl==null||sourceUrl.length()>2048||size<16||size>512)throw new IllegalArgumentException("BLOCK_TEXTURE_METADATA");}}
    public record State(long revision,Map<String,Texture> textures){public State{if(revision<0||textures.size()>64)throw new IllegalArgumentException("BLOCK_TEXTURE_STATE");textures=Map.copyOf(textures);textures.keySet().forEach(BlockTextureStore::resource);}}
    private static final ObjectMapper JSON=new ObjectMapper();private final Path root;
    public BlockTextureStore(Path game)throws Exception{var base=game.toRealPath();root=base.resolve("mineagent-runtime-data/texture-cache");Files.createDirectories(root);if(!root.toRealPath().startsWith(base))throw new IllegalStateException("BLOCK_TEXTURE_CACHE_LINK");}
    public static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static void png(byte[] bytes,int size){
        if(bytes==null||bytes.length<33||bytes.length>2*1024*1024)throw new IllegalArgumentException("BLOCK_TEXTURE_PNG");
        var buffer=java.nio.ByteBuffer.wrap(bytes);if(buffer.getLong()!=0x89504e470d0a1a0aL||buffer.getInt()!=13||buffer.getInt()!=0x49484452||buffer.getInt()!=size||buffer.getInt()!=size||size<16||size>512)throw new IllegalArgumentException("BLOCK_TEXTURE_PNG_DIMENSIONS");
    }
    public static String resource(String id){if(id==null||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||id.contains(".."))throw new IllegalArgumentException("BLOCK_TEXTURE_RESOURCE");int colon=id.indexOf(':');return "assets/"+id.substring(0,colon)+"/textures/"+id.substring(colon+1)+".png";}
    private Path directory(String scope)throws Exception{var path=root.resolve(hash(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8)));Files.createDirectories(path);if(!path.toRealPath().startsWith(root.toRealPath())||Files.isSymbolicLink(path))throw new IllegalStateException("BLOCK_TEXTURE_SCOPE_LINK");return path;}
    public State load(String scope)throws Exception{var file=directory(scope).resolve("state.json");if(!Files.exists(file))return new State(0,Map.of());if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>262144)throw new IllegalStateException("BLOCK_TEXTURE_MANIFEST");return JSON.readValue(Files.readAllBytes(file),State.class);}
    public Map<String,byte[]> images(String scope,State state)throws Exception{var dir=directory(scope);var images=new LinkedHashMap<String,byte[]>();long total=0;for(var entry:state.textures.entrySet()){var file=dir.resolve(entry.getValue().sha256+".png");if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>2*1024*1024)throw new IllegalStateException("BLOCK_TEXTURE_BLOB");byte[] bytes=Files.readAllBytes(file);png(bytes,entry.getValue().size);if(!hash(bytes).equals(entry.getValue().sha256))throw new IllegalStateException("BLOCK_TEXTURE_HASH");total+=bytes.length;if(total>32*1024*1024)throw new IllegalStateException("BLOCK_TEXTURE_CACHE_BUDGET");images.put(entry.getKey(),bytes);}return Map.copyOf(images);}
    public void save(String scope,long expected,State state,Map<String,byte[]> images)throws Exception{
        var dir=directory(scope);var lease=dir.resolve("state.lock");if(Files.isSymbolicLink(lease))throw new IllegalStateException("BLOCK_TEXTURE_SCOPE_LINK");
        try(var channel=FileChannel.open(lease,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
            if(load(scope).revision()!=expected||state.revision()!=expected+1)throw new IllegalStateException("BLOCK_TEXTURE_STALE");long total=0;
            for(var texture:state.textures.entrySet()){var bytes=images.get(texture.getKey());if(bytes==null||bytes.length>2*1024*1024||!hash(bytes).equals(texture.getValue().sha256))throw new IllegalStateException("BLOCK_TEXTURE_HASH");total+=bytes.length;if(total>32*1024*1024)throw new IllegalStateException("BLOCK_TEXTURE_CACHE_BUDGET");var blob=dir.resolve(texture.getValue().sha256+".png");if(Files.isSymbolicLink(blob))throw new IllegalStateException("BLOCK_TEXTURE_BLOB_LINK");png(bytes,texture.getValue().size);if(!Files.exists(blob))Files.write(blob,bytes,StandardOpenOption.CREATE_NEW);else if(!hash(Files.readAllBytes(blob)).equals(texture.getValue().sha256))throw new IllegalStateException("BLOCK_TEXTURE_BLOB_CHANGED");}
            var temporary=dir.resolve(UUID.randomUUID()+".json.tmp");Files.write(temporary,JSON.writeValueAsBytes(state),StandardOpenOption.CREATE_NEW);var target=dir.resolve("state.json");if(Files.isSymbolicLink(target))throw new IllegalStateException("BLOCK_TEXTURE_MANIFEST_LINK");
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }
    }
}
