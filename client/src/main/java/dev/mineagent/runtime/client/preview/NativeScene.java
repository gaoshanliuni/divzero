package dev.mineagent.runtime.client.preview;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Validated data-only preview mesh and camera projection, independent of browser and game classes. */
public final class NativeScene {
    public record Camera(double yaw,double pitch,double zoom,double panX,double panY){public Camera{for(double n:new double[]{yaw,pitch,zoom,panX,panY})if(!Double.isFinite(n))throw new IllegalArgumentException("PREVIEW_CAMERA");if(zoom<.2||zoom>6)throw new IllegalArgumentException("PREVIEW_ZOOM");}}
    public record Projection(float[] xy,int[] colors){}
    private final String title,detail;private final double[][] vertices;private final int[][] faces;private final double[] center;private final double radius;
    private NativeScene(String title,String detail,double[][] vertices,int[][] faces){this.title=title;this.detail=detail;this.vertices=vertices;this.faces=faces;double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};for(var v:vertices)for(int axis=0;axis<3;axis++){min[axis]=Math.min(min[axis],v[axis]);max[axis]=Math.max(max[axis],v[axis]);}center=new double[3];double extent=.05;for(int axis=0;axis<3;axis++){center[axis]=(min[axis]+max[axis])/2;extent=Math.max(extent,max[axis]-min[axis]);}radius=extent/2;}
    public String title(){return title;}public String detail(){return detail;}public int triangleCount(){return faces.length;}
    public static NativeScene parse(String source)throws Exception{
        if(source==null||source.length()>8*1024*1024)throw new IllegalArgumentException("PREVIEW_SCENE_SIZE");var root=new ObjectMapper().readTree(source);var points=root.path("vertices");var triangles=root.path("faces");if(!points.isArray()||points.isEmpty()||points.size()>200008||!triangles.isArray()||triangles.size()>300000)throw new IllegalArgumentException("PREVIEW_SCENE_INVALID");
        var vertices=new double[points.size()][3];for(int i=0;i<vertices.length;i++){var point=points.get(i);if(!point.isArray()||point.size()!=3)throw new IllegalArgumentException("PREVIEW_VERTEX");for(int axis=0;axis<3;axis++){var value=point.get(axis);if(!value.isNumber()||!Double.isFinite(value.doubleValue())||Math.abs(value.doubleValue())>1e9)throw new IllegalArgumentException("PREVIEW_VERTEX");vertices[i][axis]=value.doubleValue();}}
        var faces=new int[triangles.size()][4];for(int i=0;i<faces.length;i++){var face=triangles.get(i);if(!face.isArray()||face.size()!=4)throw new IllegalArgumentException("PREVIEW_FACE");for(int n=0;n<4;n++){var value=face.get(n);if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalArgumentException("PREVIEW_FACE");long v=value.longValue();if(n<3&&(v<0||v>=vertices.length)||n==3&&(v<Integer.MIN_VALUE||v>0xffffffffL))throw new IllegalArgumentException("PREVIEW_FACE");faces[i][n]=(int)v;}}
        String title=root.path("title").asText(""),detail=root.path("detail").asText("");if(title.length()>4096||detail.length()>16384)throw new IllegalArgumentException("PREVIEW_TEXT");return new NativeScene(title,detail,vertices,faces);
    }
    public Projection project(Camera camera,int width,int height){
        if(width<1||height<1||width>32768||height>32768)throw new IllegalArgumentException("PREVIEW_VIEWPORT");double co=Math.cos(camera.yaw),si=Math.sin(camera.yaw),cp=Math.cos(camera.pitch),sp=Math.sin(camera.pitch),distance=radius*3.6/camera.zoom,scale=Math.min(width,height)*.62;
        var points=new double[vertices.length][5];for(int i=0;i<vertices.length;i++){var v=vertices[i];double x=v[0]-center[0],y=v[1]-center[1],z=v[2]-center[2],rx=x*co+z*si,rz=-x*si+z*co,ry=y*cp-rz*sp;rz=y*sp+rz*cp;double d=Math.max(radius*.05,distance-rz);points[i]=new double[]{width/2.0+rx/d*scale+camera.panX,height/2.0-ry/d*scale+camera.panY,rz,rx,ry};}
        var order=new Integer[faces.length];var depth=new double[faces.length];for(int i=0;i<faces.length;i++){order[i]=i;var f=faces[i];depth[i]=(points[f[0]][2]+points[f[1]][2]+points[f[2]][2])/3;}Arrays.sort(order,Comparator.comparingDouble(i->depth[i]));
        var xy=new float[faces.length*6];var colors=new int[faces.length];int cursor=0;for(int index:order){var f=faces[index];var a=points[f[0]];var b=points[f[1]];var c=points[f[2]];double ux=b[3]-a[3],uy=b[4]-a[4],uz=b[2]-a[2],vx=c[3]-a[3],vy=c[4]-a[4],vz=c[2]-a[2],nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);double light=.4+.6*Math.max(0,(nx*.25+ny*.7+nz*.65)/Math.max(1e-12,length));int color=f[3];colors[cursor]=(color&0xff000000)|((int)Math.round((color>>>16&255)*light)<<16)|((int)Math.round((color>>>8&255)*light)<<8)|(int)Math.round((color&255)*light);for(int i=0;i<3;i++){xy[cursor*6+i*2]=(float)points[f[i]][0];xy[cursor*6+i*2+1]=(float)points[f[i]][1];}cursor++;}return new Projection(xy,colors);
    }
}
