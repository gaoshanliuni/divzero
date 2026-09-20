package dev.mineagent.runtime.neoforge.client.objects;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import dev.mineagent.runtime.neoforge.content.RuntimeThrownItemEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
public final class RuntimeThrownItemRenderer extends EntityRenderer<RuntimeThrownItemEntity,RuntimeThrownItemRenderer.State> {
    public static volatile int rendered;
    private final RuntimeItemRenderer geometry=new RuntimeItemRenderer();
    public static final class State extends EntityRenderState{RuntimeMesh mesh;}
    public RuntimeThrownItemRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.2F;}
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(RuntimeThrownItemEntity entity,State state,float partial){super.extractRenderState(entity,state,partial);state.mesh=geometry.extractArgument(entity.item());}
    @Override public void submit(State state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){super.submit(state,poses,collector,camera);poses.pushPose();poses.translate(-.5,0,-.5);geometry.submit(state.mesh,poses,collector,state.lightCoords,OverlayTexture.NO_OVERLAY,false,state.outlineColor);poses.popPose();if(state.mesh!=null&&Boolean.getBoolean("mineagent.conversationAgentSmoke"))rendered++;}
}
