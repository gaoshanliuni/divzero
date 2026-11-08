package dev.mineagent.runtime.neoforge.client.objects;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import java.util.*;
public final class RuntimeObjectRenderer extends EntityRenderer<RuntimeObjectEntity,RuntimeObjectRenderer.State> {
    public static final Map<UUID,Integer> drawn=new HashMap<>();
    public static final class State extends EntityRenderState{RuntimeObjectAssets.Asset asset;UUID entity;float yaw;}
    public RuntimeObjectRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=0.2F;}
    @Override public State createRenderState(){return new State();}
    @Override protected AABB getBoundingBoxForCulling(RuntimeObjectEntity entity){return entity.getBoundingBox().inflate(64);}
    @Override public void extractRenderState(RuntimeObjectEntity entity,State state,float partial){super.extractRenderState(entity,state,partial);state.asset=RuntimeObjectAssets.get(entity);state.entity=entity.getUUID();state.yaw=entity.getYRot();}
    @Override public void submit(State state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){
        super.submit(state,poses,collector,camera);if(state.asset==null)return;var asset=state.asset;poses.pushPose();poses.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        RuntimeMeshDraw.submit(asset.mesh(),poses,collector,state.lightCoords,OverlayTexture.NO_OVERLAY,asset::texture);
        poses.popPose();if(dev.mineagent.runtime.client.webui.RuntimeObjectTelemetry.enabled())drawn.merge(state.entity,1,Integer::sum);
    }
}
