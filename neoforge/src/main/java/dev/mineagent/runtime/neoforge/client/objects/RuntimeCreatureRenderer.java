package dev.mineagent.runtime.neoforge.client.objects;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import dev.mineagent.runtime.neoforge.content.RuntimeCreatureEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import java.util.*;
public final class RuntimeCreatureRenderer extends EntityRenderer<RuntimeCreatureEntity,RuntimeCreatureRenderer.State> {
    public static final Map<UUID,Integer> drawn=new HashMap<>();public static final Map<UUID,Set<String>> animationsSeen=new HashMap<>();
    public static final class State extends EntityRenderState{RuntimeMesh mesh;UUID entity;float yaw;boolean baby;double[] transform={0,0,0,0,0,0,1,1,1};}
    public RuntimeCreatureRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=0.2F;}
    @Override public State createRenderState(){return new State();}
    @Override protected AABB getBoundingBoxForCulling(RuntimeCreatureEntity entity){return entity.getBoundingBox().inflate(64);}
    @Override public void extractRenderState(RuntimeCreatureEntity entity,State state,float partial){super.extractRenderState(entity,state,partial);state.mesh=entity.definition()==null?null:RuntimeMesh.parse(entity.definition().model());state.baby=entity.isBaby();state.entity=entity.getUUID();state.yaw=entity.getYRot();var definition=entity.definition();if(definition!=null){var animations=definition.animations();double age=entity.tickCount+partial,t=age;String event=entity.deathTime>0?"death":entity.hurtTime>0?"hurt":entity.getAttackAnim(partial)>0?"attack":entity.interactionAnimationTicks>0?"interact":entity.isVehicle()?"ride":(Math.pow(entity.getX()-entity.xo,2)+Math.pow(entity.getZ()-entity.zo,2)>.000001||entity.getDeltaMovement().horizontalDistanceSqr()>.0001)?"walk":"idle";if(event.equals("hurt"))t=10-entity.hurtTime+partial;if(event.equals("death"))t=entity.deathTime+partial;if(event.equals("attack"))t=entity.getAttackAnim(partial)*animations.getOrDefault("attack",new dev.mineagent.runtime.core.creature.CreatureAnimation.Clip(10,false,200,List.of())).duration();if(event.equals("interact"))t=20-entity.interactionAnimationTicks+partial;var random=animations.get("random");if(event.equals("idle")&&random!=null){double phase=(age+Math.floorMod(entity.getUUID().hashCode(),random.interval()))%random.interval();if(phase<random.duration()){event="random";t=phase;}}state.transform=dev.mineagent.runtime.core.creature.CreatureAnimation.sample(animations.get(event),t);if(animations.containsKey(event)&&dev.mineagent.runtime.client.webui.RuntimeObjectTelemetry.enabled())animationsSeen.computeIfAbsent(entity.getUUID(),k->new HashSet<>()).add(event);}}
    @Override public void submit(State state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){
        super.submit(state,poses,collector,camera);if(state.mesh==null)return;poses.pushPose();if(state.baby)poses.scale(.5F,.5F,.5F);poses.mulPose(Axis.YP.rotationDegrees(-state.yaw));var poseTransform=state.transform;poses.translate(poseTransform[0],poseTransform[1],poseTransform[2]);poses.mulPose(Axis.XP.rotationDegrees((float)poseTransform[3]));poses.mulPose(Axis.YP.rotationDegrees((float)poseTransform[4]));poses.mulPose(Axis.ZP.rotationDegrees((float)poseTransform[5]));poses.scale((float)poseTransform[6],(float)poseTransform[7],(float)poseTransform[8]);
        collector.submitCustomGeometry(poses,RenderTypes.entityTranslucent(net.minecraft.resources.Identifier.fromNamespaceAndPath("mineagent_runtime","textures/entity/creature_white.png")),(pose,buffer)->{
            for(var t:state.mesh.triangles()){
                var a=state.mesh.vertices().get(t.a());var b=state.mesh.vertices().get(t.b());var c=state.mesh.vertices().get(t.c());
                float x=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),y=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),z=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());float n=(float)Math.sqrt(x*x+y*y+z*z);x/=n;y/=n;z/=n;
                vertex(buffer,pose,state.lightCoords,a,t.color(),x,y,z);vertex(buffer,pose,state.lightCoords,b,t.color(),x,y,z);vertex(buffer,pose,state.lightCoords,c,t.color(),x,y,z);vertex(buffer,pose,state.lightCoords,c,t.color(),x,y,z);
            }
        });poses.popPose();if(dev.mineagent.runtime.client.webui.RuntimeObjectTelemetry.enabled())drawn.merge(state.entity,1,Integer::sum);
    }
    private static void vertex(VertexConsumer b,PoseStack.Pose pose,int light,RuntimeMesh.Vertex v,int color,float x,float y,float z){b.addVertex(pose,v.x(),v.y(),v.z()).setColor(color).setUv(v.u(),v.v()).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,v.hasNormal()?v.nx():x,v.hasNormal()?v.ny():y,v.hasNormal()?v.nz():z);}
}
