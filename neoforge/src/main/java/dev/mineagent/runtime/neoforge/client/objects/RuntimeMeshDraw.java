package dev.mineagent.runtime.neoforge.client.objects;

import com.mojang.blaze3d.vertex.*;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import java.util.function.Function;

/** Shared item/object/creature path: one native draw per actual texture resource. */
final class RuntimeMeshDraw {
    static void submit(RuntimeMesh mesh,PoseStack poses,SubmitNodeCollector collector,int light,int overlay,Function<String,Identifier> texture){
        for(var batch:mesh.batches().entrySet())collector.submitCustomGeometry(poses,RenderTypes.entityTranslucent(texture.apply(batch.getKey())),(pose,buffer)->{
            for(var triangle:batch.getValue()){
                var a=mesh.vertices().get(triangle.a());var b=mesh.vertices().get(triangle.b());var c=mesh.vertices().get(triangle.c());
                float x=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),y=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),z=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());
                float n=(float)Math.sqrt(x*x+y*y+z*z);x/=n;y/=n;z/=n;
                vertex(buffer,pose,light,overlay,a,triangle.color(),x,y,z);vertex(buffer,pose,light,overlay,b,triangle.color(),x,y,z);vertex(buffer,pose,light,overlay,c,triangle.color(),x,y,z);vertex(buffer,pose,light,overlay,c,triangle.color(),x,y,z);
            }
        });
    }
    private static void vertex(VertexConsumer buffer,PoseStack.Pose pose,int light,int overlay,RuntimeMesh.Vertex v,int color,float x,float y,float z){
        buffer.addVertex(pose,v.x(),v.y(),v.z()).setColor(color).setUv(v.u(),v.v()).setOverlay(overlay).setLight(light).setNormal(pose,v.hasNormal()?v.nx():x,v.hasNormal()?v.ny():y,v.hasNormal()?v.nz():z);
    }
    private RuntimeMeshDraw(){}
}
