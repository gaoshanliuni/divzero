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
public final class RuntimeItemRenderer implements SpecialModelRenderer<RuntimeItemRenderer.RenderedItem> {
    public record RenderedItem(RuntimeMesh mesh,RuntimeItemBinding binding){}
    private static final Map<String,RuntimeMesh> CACHE=new LinkedHashMap<>(32,.75f,true);
    private static final Identifier WHITE=Identifier.fromNamespaceAndPath("mineagent_runtime","runtime_item_white");
    private static boolean textureReady;
    public static volatile int rendered;
    public static volatile int texturedRendered;
    public static volatile int distinctTextureBatches;public static volatile boolean transformed;
    @SubscribeEvent public static void register(net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent event){event.register(Identifier.fromNamespaceAndPath("mineagent_runtime","runtime_item"),Unbaked.CODEC);}
    public record Unbaked() implements SpecialModelRenderer.Unbaked<RenderedItem> {
        public static final MapCodec<Unbaked> CODEC=MapCodec.unit(new Unbaked());
        public MapCodec<Unbaked> type(){return CODEC;}
        public RuntimeItemRenderer bake(SpecialModelRenderer.BakingContext context){return new RuntimeItemRenderer();}
    }
    @Override public RenderedItem extractArgument(ItemStack stack){
        String raw=stack.get(dev.mineagent.runtime.neoforge.MineAgentRegistries.RUNTIME_ITEM_BINDING.get());if(raw==null||raw.length()>RuntimeItemBinding.MAX_BINDING)return null;
        var binding=RuntimeItem.binding(stack);if(binding==null)return null;var mesh=CACHE.get(raw);
        if(mesh==null){mesh=binding.mesh();if(CACHE.size()>=32)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(raw,mesh);}
        return new RenderedItem(mesh,binding);
    }
    @Override public void getExtents(Consumer<Vector3fc> output){for(int x=0;x<=1;x++)for(int y=0;y<=1;y++)for(int z=0;z<=1;z++)output.accept(new Vector3f(x,y,z));}
    @Override public void submit(RenderedItem item,PoseStack poses,SubmitNodeCollector collector,int light,int overlay,boolean foil,int outline){
        if(item==null)return;var mesh=item.mesh();
        // Held-item render arguments may be cached while an asynchronous asset download finishes.
        var asset=mesh.texturePaths().isEmpty()?null:RuntimeObjectAssets.get(item.binding());
        if(!textureReady){var image=new com.mojang.blaze3d.platform.NativeImage(1,1,false);image.setPixel(0,0,-1);Minecraft.getInstance().getTextureManager().register(WHITE,new net.minecraft.client.renderer.texture.DynamicTexture(()->"Runtime item color surface",image));textureReady=true;}
        poses.pushPose();poses.translate(.5,0,.5);
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var v:mesh.vertices()){minX=java.lang.Math.min(minX,v.x());minY=java.lang.Math.min(minY,v.y());minZ=java.lang.Math.min(minZ,v.z());maxX=java.lang.Math.max(maxX,v.x());maxY=java.lang.Math.max(maxY,v.y());maxZ=java.lang.Math.max(maxZ,v.z());}
        if(minX<-.5||maxX>.5||minY<0||maxY>1||minZ<-.5||maxZ>.5){float fit=(float)(.95/java.lang.Math.max(maxX-minX,java.lang.Math.max(maxY-minY,maxZ-minZ)));poses.scale(fit,fit,fit);poses.translate(-(minX+maxX)/2,-minY,-(minZ+maxZ)/2);}
        var before=new org.joml.Matrix4f(poses.last().pose());var transform=mesh.itemTransform();var translation=transform.translation();var rotation=transform.rotation();var scale=transform.scale();
        poses.translate(translation.get(0),translation.get(1),translation.get(2));poses.mulPose(com.mojang.math.Axis.XP.rotationDegrees(rotation.get(0).floatValue()));poses.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotation.get(1).floatValue()));poses.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rotation.get(2).floatValue()));poses.scale(scale.get(0).floatValue(),scale.get(1).floatValue(),scale.get(2).floatValue());
        RuntimeMeshDraw.submit(mesh,poses,collector,light,overlay,path->asset==null?WHITE:asset.texture(path));
        boolean matrixChanged=!before.equals(poses.last().pose());poses.popPose();if(Boolean.getBoolean("mineagent.conversationAgentSmoke")||Boolean.getBoolean("mineagent.skillSmoke")){rendered++;if(asset!=null&&!mesh.texturePaths().isEmpty()){texturedRendered++;distinctTextureBatches=mesh.batches().size();}transformed=matrixChanged;}
    }
}
