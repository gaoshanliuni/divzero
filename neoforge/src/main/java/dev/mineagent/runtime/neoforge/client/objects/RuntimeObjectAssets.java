package dev.mineagent.runtime.neoforge.client.objects;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mineagent.runtime.core.objects.*;
import dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity;
import dev.mineagent.runtime.neoforge.network.ObjectAssetPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import java.io.*;
import java.util.*;

@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class RuntimeObjectAssets {
    public static final Map<String,Integer> observedChunks=new HashMap<>();
    public record Asset(RuntimeMesh mesh,Map<String,Identifier> textures,long memory){public Asset{textures=Map.copyOf(textures);}public Identifier texture(){return texture(mesh.texture());}public Identifier texture(String path){return textures.getOrDefault(path,textures.get(""));}void release(){textures.values().forEach(Minecraft.getInstance().getTextureManager()::release);}}
    private record Entry(Asset asset,int used){}
    private static final class Pending{final UUID request=UUID.randomUUID();final int entity;final String hash;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();int total=-1,sent;boolean waiting;Pending(int entity,String hash){this.entity=entity;this.hash=hash;}}
    private static final Map<String,Entry> loaded=new LinkedHashMap<>();private static final Map<String,Pending> pending=new LinkedHashMap<>();private static final Map<String,String> errors=new LinkedHashMap<>();private static Object connection;private static int ticks;private static long memory;
    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent event){event.register(ObjectAssetPayloads.Chunk.TYPE,(p,c)->{var source=c.connection();c.enqueueWork(()->{var current=Minecraft.getInstance().getConnection();if(current!=null&&current.getConnection()==source)accept(p);});});}
    public static Asset get(RuntimeObjectEntity entity){
        if(entity.header()==null)return null;String hash=entity.header().asset();var old=loaded.get(hash);if(old!=null){loaded.put(hash,new Entry(old.asset(),ticks));return old.asset();}
        if(!errors.containsKey(hash)&&pending.size()<4)pending.computeIfAbsent(hash,k->new Pending(entity.getId(),hash));return null;
    }
    public static Asset get(dev.mineagent.runtime.neoforge.content.RuntimeCreatureEntity entity,String hash){
        if(hash==null||hash.isEmpty())return null;var old=loaded.get(hash);if(old!=null){loaded.put(hash,new Entry(old.asset(),ticks));return old.asset();}
        if(!errors.containsKey(hash)&&pending.size()<4)pending.computeIfAbsent(hash,k->new Pending(entity.getId(),hash));return null;
    }
    public static Asset get(RuntimeItemBinding binding){
        String asset=binding.assetHash();var old=loaded.get(asset);if(old!=null){loaded.put(asset,new Entry(old.asset(),ticks));return old.asset();}
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||errors.containsKey(asset)||pending.size()>=4)return null;
        net.minecraft.world.entity.Entity holder=null;
        if(dev.mineagent.runtime.neoforge.content.RuntimeItemAssets.visible(mc.player,mc.player,asset)!=null)holder=mc.player;
        else for(var entity:mc.level.entitiesForRendering())if(mc.player.distanceToSqr(entity)<=128*128&&dev.mineagent.runtime.neoforge.content.RuntimeItemAssets.visible(entity,mc.player,asset)!=null){holder=entity;break;}
        if(holder!=null){int id=holder.getId();pending.computeIfAbsent(asset,k->new Pending(id,asset));}return null;
    }
    private static boolean bound(net.minecraft.world.entity.Entity entity,String hash){if(Minecraft.getInstance().player!=null&&dev.mineagent.runtime.neoforge.content.RuntimeItemAssets.visible(entity,Minecraft.getInstance().player,hash)!=null)return true;return entity instanceof RuntimeObjectEntity object&&object.header()!=null&&object.header().asset().equals(hash)||entity instanceof dev.mineagent.runtime.neoforge.content.RuntimeCreatureEntity creature&&creature.definition()!=null&&creature.definition().modelAssets().containsValue(hash);}
    public static String diagnostic(String hash){return loaded.containsKey(hash)?"READY":errors.getOrDefault(hash,"LOADING");}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        ticks++;var mc=Minecraft.getInstance();Object current=mc.getConnection()==null?null:mc.getConnection().getConnection();if(current!=connection){clear();connection=current;}
        if(current==null||mc.level==null)return;int budget=4;
        for(var p:List.copyOf(pending.values())){
            var e=mc.level.getEntity(p.entity);if(!bound(e,p.hash)){pending.remove(p.hash);continue;}
            if(p.waiting&&ticks-p.sent>100){fail(p,"OBJECT_ASSET_TIMEOUT");continue;}
            if(!p.waiting&&budget-->0){p.waiting=true;p.sent=ticks;ClientPacketDistributor.sendToServer(new ObjectAssetPayloads.Request(p.request,p.entity,p.hash,p.bytes.size()));}
        }
        for(var entry:List.copyOf(loaded.entrySet()))if(ticks-entry.getValue().used()>200){entry.getValue().asset().release();memory-=entry.getValue().asset().memory();loaded.remove(entry.getKey());}
    }
    private static void accept(ObjectAssetPayloads.Chunk p){
        var waiting=pending.get(p.hash());if(waiting==null||!waiting.request.equals(p.request())||waiting.entity!=p.entity()||!waiting.waiting)return;
        if(!p.error().isEmpty()){fail(waiting,p.error());return;}
        if(p.offset()!=waiting.bytes.size()||p.bytes().length==0||p.total()<1||waiting.total!=-1&&waiting.total!=p.total()){fail(waiting,"OBJECT_CHUNK_SEQUENCE");return;}
        if(dev.mineagent.runtime.client.webui.RuntimeObjectTelemetry.enabled())observedChunks.merge(p.hash(),1,Integer::sum);
        waiting.total=p.total();waiting.bytes.writeBytes(p.bytes());waiting.waiting=false;if(waiting.bytes.size()!=waiting.total)return;
        var images=new TreeMap<String,NativeImage>();var registered=new LinkedHashMap<String,Identifier>();boolean retained=false;
        try{
            var bundle=RuntimeModelBundle.decode(waiting.bytes.toByteArray(),p.hash());var white=new NativeImage(1,1,false);white.setPixel(0,0,-1);images.put("",white);
            long bytes=bundle.size()+4+32L*bundle.mesh().vertices().size()+24L*bundle.mesh().triangles().size();
            for(var entry:bundle.textures().entrySet()){var image=NativeImage.read(new ByteArrayInputStream(entry.getValue()));images.put(entry.getKey(),image);if(image.getWidth()>1024||image.getHeight()>1024)throw new IllegalStateException("OBJECT_TEXTURE_DIMENSIONS");bytes+=4L*image.getWidth()*image.getHeight();}
            while(!loaded.isEmpty()&&memory+bytes>64L*1024*1024){var oldest=loaded.entrySet().stream().min(Comparator.comparingInt(e->e.getValue().used())).orElseThrow();oldest.getValue().asset().release();memory-=oldest.getValue().asset().memory();loaded.remove(oldest.getKey());}
            if(memory+bytes>64L*1024*1024)throw new IllegalStateException("OBJECT_GPU_BUDGET");
            int index=0;for(var entry:List.copyOf(images.entrySet())){Identifier texture=Identifier.fromNamespaceAndPath("mineagent_runtime","objects/"+p.hash()+"/"+(index++));Minecraft.getInstance().getTextureManager().register(texture,new DynamicTexture(()->"Runtime object "+p.hash(),entry.getValue()));images.remove(entry.getKey());registered.put(entry.getKey(),texture);}
            loaded.put(p.hash(),new Entry(new Asset(bundle.mesh(),registered,bytes),ticks));memory+=bytes;pending.remove(p.hash());retained=true;
        }catch(Exception failure){fail(waiting,"OBJECT_ASSET_INVALID");}finally{images.values().forEach(NativeImage::close);if(!retained)registered.values().forEach(Minecraft.getInstance().getTextureManager()::release);}
    }
    private static void fail(Pending p,String code){pending.remove(p.hash);if(errors.size()<128)errors.put(p.hash,code);dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Runtime object asset {}: {}",p.hash,code);}
    private static void clear(){for(var e:loaded.values())e.asset().release();loaded.clear();pending.clear();errors.clear();memory=0;}
}
