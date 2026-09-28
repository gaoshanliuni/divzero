package dev.mineagent.runtime.neoforge.client.resources;

import com.fasterxml.jackson.databind.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mineagent.runtime.client.resources.BlockTextureStore;
import dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt;
import dev.mineagent.runtime.neoforge.mixin.client.SpritePixelsAccess;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One non-executable image pack bound to the current signed world and player. Never edits vanilla files or options. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class BlockTextureClient {
    private static final String PACK="mineagent_runtime:world_texture_overrides";
    private static final ObjectMapper JSON=new ObjectMapper();private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static volatile Map<String,byte[]> published=Map.of();
    private static Map<String,byte[]> images=Map.of();private static BlockTextureStore.State state=new BlockTextureStore.State(0,Map.of());
    private static String scope="",error="";private static Object connection;private static long epoch;private static boolean ready,busy;
    private record Incoming(JsonNode args,Object connection,String scope,int expectedBytes,int chunks,ByteArrayOutputStream bytes,long deadline){int next(){return (bytes.size()+59999)/60000;}}
    private static final Map<UUID,Incoming> incoming=new LinkedHashMap<>();private static final Map<UUID,String> receipts=new LinkedHashMap<>();
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static String scope(){
        if(mc().player==null||mc().level==null||mc().getConnection()==null||!MineAgentClientTrustPrompt.enabled()||!PanelSnapshotInbox.signatureValid())return "";
        var values=PanelSnapshotInbox.snapshot().values();String world=values.getOrDefault("security.worldId",""),fingerprint=values.getOrDefault("security.identityFingerprint",""),instance=values.getOrDefault("security.configInstance","");
        if(world.isBlank()||fingerprint.isBlank()||instance.isBlank())return "";return (mc().getCurrentServer()==null?"local-integrated":mc().getCurrentServer().ip)+"|"+fingerprint+"|"+instance+"|"+world+"|"+mc().player.getUUID();
    }
    private static BlockTextureStore store()throws Exception{return new BlockTextureStore(mc().gameDirectory.toPath());}
    @SubscribeEvent public static void packs(net.neoforged.neoforge.event.AddPackFindersEvent event){if(event.getPackType()==PackType.CLIENT_RESOURCES)event.addRepositorySource(output->{var files=published;if(files.isEmpty())return;var info=new PackLocationInfo(PACK,Component.literal("DivZero World Textures"),PackSource.create(PackSource.NO_DECORATION,false),Optional.empty());output.accept(new Pack(info,new Pack.ResourcesSupplier(){public PackResources openPrimary(PackLocationInfo location){return new ImagePack(location,files);}public PackResources openFull(PackLocationInfo location,Pack.Metadata metadata){return new ImagePack(location,files);}},new Pack.Metadata(Component.literal("World-scoped image overrides"),PackCompatibility.COMPATIBLE,net.minecraft.world.flag.FeatureFlagSet.of(),List.of()),new PackSelectionConfig(true,Pack.Position.TOP,true)));});}
    private static final class ImagePack extends AbstractPackResources {
        private final Map<String,byte[]> files;private final Set<String> namespaces;
        ImagePack(PackLocationInfo info,Map<String,byte[]> images){super(info);var copy=new LinkedHashMap<String,byte[]>();var namespaces=new HashSet<String>();images.forEach((id,bytes)->{String path=BlockTextureStore.resource(id);copy.put(path,bytes);copy.put(path+".mcmeta","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));namespaces.add(id.substring(0,id.indexOf(':')));});this.files=Map.copyOf(copy);this.namespaces=Set.copyOf(namespaces);}
        private IoSupplier<InputStream> data(String path){var bytes=files.get(path);return bytes==null?null:()->new ByteArrayInputStream(bytes);}
        @Override public IoSupplier<InputStream> getRootResource(String... path){return null;}
        @Override public IoSupplier<InputStream> getResource(PackType type,Identifier id){return type==PackType.CLIENT_RESOURCES?data("assets/"+id.getNamespace()+"/"+id.getPath()):null;}
        @Override public Set<String> getNamespaces(PackType type){return type==PackType.CLIENT_RESOURCES?namespaces:Set.of();}
        @Override public void listResources(PackType type,String namespace,String directory,PackResources.ResourceOutput out){if(type!=PackType.CLIENT_RESOURCES)return;String prefix="assets/"+namespace+"/",search=prefix+directory+(directory.isEmpty()?"":"/");for(String path:files.keySet())if(path.startsWith(search))out.accept(Identifier.fromNamespaceAndPath(namespace,path.substring(prefix.length())),data(path));}
        @Override public void close(){}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        String next=scope();if(!next.equals(scope)||connection!=mc().getConnection()){scope=next;connection=mc().getConnection();epoch++;ready=false;incoming.clear();receipts.clear();}
        for(var entry:List.copyOf(incoming.entrySet()))if(System.currentTimeMillis()>=entry.getValue().deadline()){incoming.remove(entry.getKey());reply(entry.getKey(),entry.getValue().connection(),Map.of("status","REJECTED","error","BLOCK_TEXTURE_TRANSFER_TIMEOUT"));}
        if(ready||busy)return;busy=true;long token=epoch;String selected=scope;boolean had=!published.isEmpty();
        CompletableFuture.supplyAsync(()->{try{if(selected.isEmpty())return Map.entry(new BlockTextureStore.State(0,Map.of()),Map.<String,byte[]>of());var store=store();var loaded=store.load(selected);var data=store.images(selected,loaded);for(var item:data.entrySet())try(var decoded=NativeImage.read(item.getValue())){if(decoded.getWidth()!=loaded.textures().get(item.getKey()).size()||decoded.getHeight()!=decoded.getWidth())throw new IllegalArgumentException("BLOCK_TEXTURE_CACHE_IMAGE");}return Map.entry(loaded,data);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((loaded,failure)->mc().execute(()->{
            if(token!=epoch){busy=false;return;}if(failure!=null){state=new BlockTextureStore.State(0,Map.of());images=Map.of();error="BLOCK_TEXTURE_CACHE_READ_FAILED";}else{state=loaded.getKey();images=loaded.getValue();error="";}published=images;
            (had||!images.isEmpty()?reload():CompletableFuture.<Void>completedFuture(null)).whenComplete((ignored,reloadError)->mc().execute(()->{busy=false;if(token==epoch){ready=true;if(reloadError!=null)error="BLOCK_TEXTURE_RESTORE_RELOAD_FAILED";}}));
        }));
    }
    private static CompletableFuture<Void> reload(){try{var repository=mc().getResourcePackRepository();var selected=new ArrayList<>(repository.getSelectedIds());selected.remove(PACK);repository.reload();if(!published.isEmpty())selected.add(PACK);repository.setSelected(selected);return mc().reloadResourcePacks();}catch(Exception failure){return CompletableFuture.failedFuture(failure);}}
    private static Map<String,TextureAtlasSprite> sprites(String block){
        var id=Identifier.parse(block);if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("BLOCK_TEXTURE_BLOCK");var value=BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();var model=mc().getModelManager().getBlockStateModelSet().get(value);var parts=new ArrayList<BlockStateModelPart>();model.collectParts(net.minecraft.util.RandomSource.create(0),parts);
        var result=new TreeMap<String,TextureAtlasSprite>();var particle=model.particleMaterial().sprite();result.put(particle.contents().name().toString(),particle);int quads=0;
        var directions=new ArrayList<Direction>(Arrays.asList(Direction.values()));directions.add(null);for(var part:parts){for(var direction:directions)for(var quad:part.getQuads(direction)){if(++quads>16384)throw new IllegalArgumentException("BLOCK_TEXTURE_MODEL_COMPLEXITY");var sprite=quad.materialInfo().sprite();result.put(sprite.contents().name().toString(),sprite);}}
        result.remove("minecraft:missingno");if(result.isEmpty()||result.size()>64)throw new IllegalArgumentException("BLOCK_TEXTURE_MODEL_UNSUPPORTED");result.keySet().forEach(BlockTextureStore::resource);return result;
    }
    public static void accept(UiPayloads.Event packet){
        if(receipts.containsKey(packet.requestId())){if(mc().getConnection()!=null)net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new UiPayloads.Command(packet.requestId(),"blockTextureReply",receipts.get(packet.requestId())));return;}
        try{
            var args=JSON.readTree(packet.json());String kind=args.path("kind").asText();
            if(kind.equals("chunk")){
                var transfer=incoming.get(packet.requestId());if(transfer==null)throw new IllegalArgumentException("BLOCK_TEXTURE_TRANSFER_MISSING");if(transfer.connection()!=mc().getConnection()||!transfer.scope().equals(scope()))throw new IllegalStateException("BLOCK_TEXTURE_CONTEXT_CHANGED");
                if(args.path("index").asInt(-1)!=transfer.next())throw new IllegalArgumentException("BLOCK_TEXTURE_CHUNK_ORDER");byte[] data=Base64.getDecoder().decode(args.path("data").asText());if(args.path("index").asInt(-1)>=transfer.chunks()||data.length!=Math.min(60000,transfer.expectedBytes()-transfer.bytes().size()))throw new IllegalArgumentException("BLOCK_TEXTURE_CHUNK_SIZE");transfer.bytes().writeBytes(data);
                if(transfer.bytes().size()==transfer.expectedBytes()){incoming.remove(packet.requestId());byte[] bytes=transfer.bytes().toByteArray();if(!BlockTextureStore.hash(bytes).equals(transfer.args().path("sha256").asText()))throw new IllegalArgumentException("BLOCK_TEXTURE_HASH");apply(packet.requestId(),transfer.args(),bytes,transfer.connection());}return;
            }
            if(scope().isEmpty()||!scope().equals(scope)||!ready)throw new IllegalStateException("BLOCK_TEXTURE_CONTEXT_LOADING");
            if(mc().player==null||!mc().player.getUUID().toString().equals(args.path("owner").asText())||!PanelSnapshotInbox.snapshot().values().getOrDefault("security.worldId","").equals(args.path("world").asText()))throw new IllegalStateException("BLOCK_TEXTURE_CONTEXT_CHANGED");
            var actual=sprites(args.path("block").asText());
            if(kind.equals("inspect")){reply(packet.requestId(),mc().getConnection(),Map.of("status","OBSERVED","revision",state.revision(),"block",args.path("block").asText(),"textures",actual.entrySet().stream().map(e->Map.of("id",e.getKey(),"width",e.getValue().contents().width(),"height",e.getValue().contents().height(),"overridden",state.textures().containsKey(e.getKey()))).toList(),"overrides",state.textures(),"busy",busy,"error",error,"scope","CURRENT_PLAYER_WORLD_TEXTURE_RESOURCES"));return;}
            if(busy)throw new IllegalStateException("BLOCK_TEXTURE_RELOAD_BUSY");if(args.path("expectedRevision").asLong(-1)!=state.revision())throw new IllegalStateException("BLOCK_TEXTURE_STALE");
            if(kind.equals("clear")){apply(packet.requestId(),args,null,mc().getConnection());return;}
            if(!kind.equals("set"))throw new IllegalArgumentException("BLOCK_TEXTURE_ACTION");int bytes=args.path("bytes").asInt(),chunks=args.path("chunks").asInt();if(bytes<1||bytes>2*1024*1024||chunks!=(bytes+59999)/60000||incoming.size()>=16)throw new IllegalArgumentException("BLOCK_TEXTURE_TRANSFER_SIZE");
            if(incoming.putIfAbsent(packet.requestId(),new Incoming(args,mc().getConnection(),scope,bytes,chunks,new ByteArrayOutputStream(bytes),System.currentTimeMillis()+30000))!=null)throw new IllegalArgumentException("BLOCK_TEXTURE_DUPLICATE_TRANSFER");
        }catch(Exception failure){incoming.remove(packet.requestId());reply(packet.requestId(),mc().getConnection(),Map.of("status","REJECTED","error",code(failure)));}
    }
    private static void apply(UUID operation,JsonNode args,byte[] bytes,Object expectedConnection)throws Exception{
        if(busy||!ready||!scope.equals(scope())||mc().getConnection()!=expectedConnection)throw new IllegalStateException("BLOCK_TEXTURE_RELOAD_BUSY");long expected=args.path("expectedRevision").asLong(-1);if(expected!=state.revision())throw new IllegalStateException("BLOCK_TEXTURE_STALE");
        String block=args.path("block").asText();var actual=sprites(block);var targets=new ArrayList<>(actual.keySet());if(args.has("texture")){String texture=args.path("texture").asText();if(!actual.containsKey(texture))throw new IllegalArgumentException("BLOCK_TEXTURE_NOT_USED_BY_BLOCK");targets.clear();targets.add(texture);}
        int size=args.path("size").asInt(0);if(bytes!=null)BlockTextureStore.png(bytes,size);if(bytes!=null)try(var decoded=NativeImage.read(bytes)){if(size<16||size>512||decoded.getWidth()!=size||decoded.getHeight()!=size)throw new IllegalArgumentException("BLOCK_TEXTURE_IMAGE_SIZE");}
        var before=state;var oldImages=images;var records=new LinkedHashMap<>(state.textures());var replacement=new LinkedHashMap<>(images);
        for(String target:targets){if(bytes==null){records.remove(target);replacement.remove(target);}else{records.put(target,new BlockTextureStore.Texture(args.path("sha256").asText(),args.path("sourceUrl").asText(),size));replacement.put(target,bytes);}}
        var after=new BlockTextureStore.State(expected+1,records);long budget=replacement.values().stream().mapToLong(value->value.length).sum();if(budget>32*1024*1024)throw new IllegalArgumentException("BLOCK_TEXTURE_CACHE_BUDGET");
        busy=true;long token=epoch;String selected=scope;published=Map.copyOf(replacement);var result=new CompletableFuture<Map<String,Object>>();
        reload().whenComplete((unused,reloadError)->mc().execute(()->{
            try{
                if(token!=epoch||!selected.equals(scope())||mc().getConnection()!=expectedConnection)throw new IllegalStateException("BLOCK_TEXTURE_CONTEXT_CHANGED");if(reloadError!=null)throw new IllegalStateException("BLOCK_TEXTURE_RELOAD_FAILED");
                var checked=sprites(block);for(String target:targets){if(bytes!=null)verify(target,bytes,checked.get(target));else{var name=Identifier.parse(target);if(mc().getResourceManager().getResourceOrThrow(Identifier.fromNamespaceAndPath(name.getNamespace(),"textures/"+name.getPath()+".png")).sourcePackId().equals(PACK))throw new IllegalStateException("BLOCK_TEXTURE_CLEAR_NOT_EFFECTIVE");}}
                CompletableFuture.runAsync(()->{try{store().save(selected,expected,after,replacement);}catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((saved,saveError)->mc().execute(()->{
                    if(saveError!=null){result.completeExceptionally(saveError);return;}if(token!=epoch||!selected.equals(scope())){result.completeExceptionally(new IllegalStateException("BLOCK_TEXTURE_CONTEXT_CHANGED"));return;}
                    state=after;images=Map.copyOf(replacement);error="";result.complete(Map.of("status","APPLIED","revision",after.revision(),"sha256",bytes==null?"":args.path("sha256").asText(),"block",block,"textures",targets,"verifiedSprites",bytes==null?0:targets.size(),"scope","CURRENT_PLAYER_WORLD_TEXTURE_RESOURCES"));
                }));
            }catch(Exception failure){result.completeExceptionally(failure);}
        }));
        result.whenComplete((receipt,failure)->mc().execute(()->{
            if(failure==null){busy=false;reply(operation,expectedConnection,receipt);return;}
            error=code(failure);if(token!=epoch||!selected.equals(scope())){busy=false;reply(operation,expectedConnection,Map.of("status","UNKNOWN","error","BLOCK_TEXTURE_CONTEXT_CHANGED"));return;}
            state=before;images=oldImages;published=oldImages;reload().whenComplete((ignored,rollbackError)->mc().execute(()->{busy=false;reply(operation,expectedConnection,Map.of("status",rollbackError==null?"REJECTED":"UNKNOWN","error",error,"previousTexturesRestored",rollbackError==null));}));
        }));
    }
    private static void verify(String id,byte[] bytes,TextureAtlasSprite sprite)throws Exception{
        if(sprite==null)throw new IllegalStateException("BLOCK_TEXTURE_MODEL_CHANGED");var name=Identifier.parse(id);var resource=Identifier.fromNamespaceAndPath(name.getNamespace(),"textures/"+name.getPath()+".png");var resolved=mc().getResourceManager().getResourceOrThrow(resource);
        if(!resolved.sourcePackId().equals(PACK))throw new IllegalStateException("BLOCK_TEXTURE_OVERRIDDEN_BY_OTHER_PACK");
        try(var expected=NativeImage.read(bytes)){var actual=((SpritePixelsAccess)sprite.contents()).divzero$pixels();if(actual.getWidth()!=expected.getWidth()||actual.getHeight()!=expected.getHeight())throw new IllegalStateException("BLOCK_TEXTURE_ATLAS_SIZE");for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++)if(actual.getPixel(x,y)!=expected.getPixel(x,y))throw new IllegalStateException("BLOCK_TEXTURE_ATLAS_PIXELS");}
    }
    private static void reply(UUID id,Object expectedConnection,Map<String,Object> data){if(expectedConnection==null||expectedConnection!=mc().getConnection())return;try{String text=JSON.writeValueAsString(data);receipts.put(id,text);while(receipts.size()>128)receipts.remove(receipts.keySet().iterator().next());net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new UiPayloads.Command(id,"blockTextureReply",text));}catch(Exception ignored){error="BLOCK_TEXTURE_REPLY_FAILED";}}
    private static String code(Throwable error){for(var cause=error;cause!=null;cause=cause.getCause())if(cause.getMessage()!=null&&cause.getMessage().matches("BLOCK_TEXTURE_[A-Z0-9_]+"))return cause.getMessage();return "BLOCK_TEXTURE_FAILED";}
    private BlockTextureClient(){}
}
