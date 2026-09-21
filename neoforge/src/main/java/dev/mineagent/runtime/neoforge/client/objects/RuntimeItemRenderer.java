package dev.mineagent.runtime.neoforge.client.objects;

import com.mojang.blaze3d.vertex.*;
import com.mojang.serialization.MapCodec;
import dev.mineagent.runtime.core.objects.*;
import dev.mineagent.runtime.neoforge.content.RuntimeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.*;
import java.util.*;
import java.util.function.Consumer;

@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class RuntimeItemRenderer implements SpecialModelRenderer<RuntimeMesh> {
    private static final Map<String,RuntimeMesh> CACHE=new LinkedHashMap<>(32,.75f,true);
    private static final Identifier WHITE=Identifier.fromNamespaceAndPath("mineagent_runtime","runtime_item_white");
    private static boolean textureReady;
    public static volatile int rendered;
    @SubscribeEvent public static void register(net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent event){event.register(Identifier.fromNamespaceAndPath("mineagent_runtime","runtime_item"),Unbaked.CODEC);}
    public record Unbaked() implements SpecialModelRenderer.Unbaked<RuntimeMesh> {
        public static final MapCodec<Unbaked> CODEC=MapCodec.unit(new Unbaked());
        public MapCodec<Unbaked> type(){return CODEC;}
        public RuntimeItemRenderer bake(SpecialModelRenderer.BakingContext context){return new RuntimeItemRenderer();}
    }
    @Override public RuntimeMesh extractArgument(ItemStack stack){
        String raw=stack.get(dev.mineagent.runtime.neoforge.MineAgentRegistries.RUNTIME_ITEM_BINDING.get());if(raw==null||raw.length()>RuntimeItemBinding.MAX_BINDING)return null;
        var mesh=CACHE.get(raw);if(mesh!=null)return mesh;
        var binding=RuntimeItem.binding(stack);if(binding==null)return null;mesh=binding.mesh();if(CACHE.size()>=32)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(raw,mesh);return mesh;
    }
    @Override public void getExtents(Consumer<Vector3fc> output){for(int x=0;x<=1;x++)for(int y=0;y<=1;y++)for(int z=0;z<=1;z++)output.accept(new Vector3f(x,y,z));}
    @Override public void submit(RuntimeMesh mesh,PoseStack poses,SubmitNodeCollector collector,int light,int overlay,boolean foil,int outline){
        if(mesh==null)return;
        if(!textureReady){var image=new com.mojang.blaze3d.platform.NativeImage(1,1,false);image.setPixel(0,0,-1);Minecraft.getInstance().getTextureManager().register(WHITE,new net.minecraft.client.renderer.texture.DynamicTexture(()->"Runtime item color surface",image));textureReady=true;}
        poses.pushPose();poses.translate(.5,0,.5);
        collector.submitCustomGeometry(poses,RenderTypes.entityTranslucent(WHITE),(pose,buffer)->{
            for(var t:mesh.triangles()){
                var a=mesh.vertices().get(t.a());var b=mesh.vertices().get(t.b());var c=mesh.vertices().get(t.c());
                float x=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),y=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),z=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());float n=(float)java.lang.Math.sqrt(x*x+y*y+z*z);
                vertex(buffer,pose,light,overlay,a,t.color(),x/n,y/n,z/n);vertex(buffer,pose,light,overlay,b,t.color(),x/n,y/n,z/n);vertex(buffer,pose,light,overlay,c,t.color(),x/n,y/n,z/n);vertex(buffer,pose,light,overlay,c,t.color(),x/n,y/n,z/n);
            }
        });poses.popPose();if(Boolean.getBoolean("mineagent.conversationAgentSmoke"))rendered++;
    }
    private static void vertex(VertexConsumer b,PoseStack.Pose pose,int light,int overlay,RuntimeMesh.Vertex v,int color,float x,float y,float z){b.addVertex(pose,v.x(),v.y(),v.z()).setColor(color).setUv(v.u(),v.v()).setOverlay(overlay).setLight(light).setNormal(pose,v.hasNormal()?v.nx():x,v.hasNormal()?v.ny():y,v.hasNormal()?v.nz():z);}
}
