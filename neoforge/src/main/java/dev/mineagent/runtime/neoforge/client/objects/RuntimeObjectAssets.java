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
    public record Asset(RuntimeMesh mesh,Identifier texture,long memory){}
    private record Entry(Asset asset,int used){}
    private static final class Pending{final UUID request=UUID.randomUUID();final int entity;final String hash;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();int total=-1,sent;boolean waiting;Pending(int entity,String hash){this.entity=entity;this.hash=hash;}}
    private static final Map<String,Entry> loaded=new LinkedHashMap<>();private static final Map<String,Pending> pending=new LinkedHashMap<>();private static final Map<String,String> errors=new LinkedHashMap<>();private static Object connection;private static int ticks;private static long memory;
    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent event){event.register(ObjectAssetPayloads.Chunk.TYPE,(p,c)->{var source=c.connection();c.enqueueWork(()->{var current=Minecraft.getInstance().getConnection();if(current!=null&&current.getConnection()==source)accept(p);});});}
    public static Asset get(RuntimeObjectEntity entity){
        if(entity.header()==null)return null;String hash=entity.header().asset();var old=loaded.get(hash);if(old!=null){loaded.put(hash,new Entry(old.asset(),ticks));return old.asset();}
        if(!errors.containsKey(hash)&&pending.size()<4)pending.computeIfAbsent(hash,k->new Pending(entity.getId(),hash));return null;
    }
    public static String diagnostic(String hash){return loaded.containsKey(hash)?"READY":errors.getOrDefault(hash,"LOADING");}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        ticks++;var mc=Minecraft.getInstance();Object current=mc.getConnection()==null?null:mc.getConnection().getConnection();if(current!=connection){clear();connection=current;}
        if(current==null||mc.level==null)return;int budget=4;
        for(var p:List.copyOf(pending.values())){
            var e=mc.level.getEntity(p.entity);if(!(e instanceof RuntimeObjectEntity object)||object.header()==null||!object.header().asset().equals(p.hash)){pending.remove(p.hash);continue;}
            if(p.waiting&&ticks-p.sent>100){fail(p,"OBJECT_ASSET_TIMEOUT");continue;}
            if(!p.waiting&&budget-->0){p.waiting=true;p.sent=ticks;ClientPacketDistributor.sendToServer(new ObjectAssetPayloads.Request(p.request,p.entity,p.hash,p.bytes.size()));}
        }
        for(var entry:List.copyOf(loaded.entrySet()))if(ticks-entry.getValue().used()>200){mc.getTextureManager().release(entry.getValue().asset().texture());memory-=entry.getValue().asset().memory();loaded.remove(entry.getKey());}
    }
    private static void accept(ObjectAssetPayloads.Chunk p){
        var waiting=pending.get(p.hash());if(waiting==null||!waiting.request.equals(p.request())||waiting.entity!=p.entity()||!waiting.waiting)return;
        if(!p.error().isEmpty()){fail(waiting,p.error());return;}
        if(p.offset()!=waiting.bytes.size()||p.bytes().length==0||p.total()<1||waiting.total!=-1&&waiting.total!=p.total()){fail(waiting,"OBJECT_CHUNK_SEQUENCE");return;}
        if(dev.mineagent.runtime.client.webui.RuntimeObjectTelemetry.enabled())observedChunks.merge(p.hash(),1,Integer::sum);
        waiting.total=p.total();waiting.bytes.writeBytes(p.bytes());waiting.waiting=false;if(waiting.bytes.size()!=waiting.total)return;
        NativeImage image=null;
        try{
            var bundle=RuntimeModelBundle.decode(waiting.bytes.toByteArray(),p.hash());byte[] png=bundle.texture();
            if(png.length==0){image=new NativeImage(1,1,false);image.setPixel(0,0,-1);}else image=NativeImage.read(new ByteArrayInputStream(png));
            if(image.getWidth()>1024||image.getHeight()>1024)throw new IllegalStateException("OBJECT_TEXTURE_DIMENSIONS");long bytes=bundle.size()+4L*image.getWidth()*image.getHeight()+20L*bundle.mesh().vertices().size()+16L*bundle.mesh().triangles().size();
            if(loaded.size()>=64||memory+bytes>64L*1024*1024)throw new IllegalStateException("OBJECT_GPU_BUDGET");
            Identifier texture=Identifier.fromNamespaceAndPath("mineagent_runtime","objects/"+p.hash());Minecraft.getInstance().getTextureManager().register(texture,new DynamicTexture(()->"Runtime object "+p.hash(),image));image=null;
            loaded.put(p.hash(),new Entry(new Asset(bundle.mesh(),texture,bytes),ticks));memory+=bytes;pending.remove(p.hash());
        }catch(Exception failure){fail(waiting,"OBJECT_ASSET_INVALID");}finally{if(image!=null)image.close();}
    }
    private static void fail(Pending p,String code){pending.remove(p.hash);if(errors.size()<128)errors.put(p.hash,code);dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Runtime object asset {}: {}",p.hash,code);}
    private static void clear(){for(var e:loaded.values())Minecraft.getInstance().getTextureManager().release(e.asset().texture());loaded.clear();pending.clear();errors.clear();memory=0;}
}
