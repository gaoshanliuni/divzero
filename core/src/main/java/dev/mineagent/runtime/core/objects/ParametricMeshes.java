package dev.mineagent.runtime.core.objects;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import dev.mineagent.runtime.core.objects.RuntimeMesh.*;

/** Generic render geometry, not a finished object/gameplay template. All counts are checked before allocation. */
final class ParametricMeshes {
    private ParametricMeshes(){}
    static void append(JsonNode p,List<Vertex> vertices,List<Triangle> triangles){
        String type=p.path("type").asText();if(BasicMeshes.TYPES.contains(type)){BasicMeshes.append(p,vertices,triangles);return;}boolean sphere=type.equals("sphere");
        if(!sphere&&!type.equals("torus"))throw new IllegalArgumentException("MESH_PRIMITIVE_TYPE");
        RuntimeMesh.keys(p,sphere?Set.of("type","center","radius","segments","rings","color","smooth"):Set.of("type","center","majorRadius","minorRadius","segments","tubeSegments","axis","color","smooth"));
        RuntimeMesh.arraySize(p.path("center"),3);double[] center=new double[3];for(int i=0;i<3;i++)center[i]=RuntimeMesh.number(p.path("center").get(i),-32,32);
        int segments=integer(p,"segments",64,8,96),color=RuntimeMesh.color(p.path("color"));
        if(p.has("smooth")&&!p.path("smooth").isBoolean())throw new IllegalArgumentException("MESH_SMOOTH_BOOLEAN");boolean smooth=p.path("smooth").asBoolean(true);
        if(sphere){int rings=integer(p,"rings",32,4,48);double radius=RuntimeMesh.number(p.path("radius"),.001,16);budget(vertices,triangles,2+(rings-1)*(segments+1),2*segments*(rings-1));sphere(vertices,triangles,center,radius,segments,rings,color,smooth);}
        else{int tube=integer(p,"tubeSegments",8,4,48);double major=RuntimeMesh.number(p.path("majorRadius"),.001,16),minor=RuntimeMesh.number(p.path("minorRadius"),.0001,16);if(minor>=major)throw new IllegalArgumentException("MESH_TORUS_RADII");if(p.has("axis")&&!p.path("axis").isTextual())throw new IllegalArgumentException("MESH_TORUS_AXIS");String axis=p.path("axis").asText("y");if(!Set.of("x","y","z").contains(axis))throw new IllegalArgumentException("MESH_TORUS_AXIS");budget(vertices,triangles,(segments+1)*(tube+1),2*segments*tube);torus(vertices,triangles,center,major,minor,segments,tube,axis,color,smooth);}
    }
    private static int integer(JsonNode p,String key,int fallback,int min,int max){if(!p.has(key))return fallback;var n=p.path(key);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)throw new IllegalArgumentException("MESH_SEGMENT_RANGE");return n.intValue();}
    static void budget(List<Vertex> vertices,List<Triangle> triangles,int addedVertices,int addedTriangles){if(vertices.size()+addedVertices>RuntimeMesh.MAX_VERTICES||triangles.size()+addedTriangles>RuntimeMesh.MAX_TRIANGLES)throw new IllegalArgumentException("MESH_EXPANDED_BUDGET");}
    private static void vertex(List<Vertex> out,double[] center,double x,double y,double z,double u,double v,double nx,double ny,double nz,boolean smooth){double X=center[0]+x,Y=center[1]+y,Z=center[2]+z;if(!Double.isFinite(X)||!Double.isFinite(Y)||!Double.isFinite(Z)||Math.abs(X)>32||Math.abs(Y)>32||Math.abs(Z)>32)throw new IllegalArgumentException("MESH_PRIMITIVE_BOUNDS");out.add(new Vertex((float)X,(float)Y,(float)Z,(float)u,(float)v,smooth?(float)nx:0,smooth?(float)ny:0,smooth?(float)nz:0));}
    private static void sphere(List<Vertex> v,List<Triangle> t,double[] center,double r,int segments,int rings,int color,boolean smooth){
        int base=v.size();vertex(v,center,0,r,0,.5,0,0,1,0,smooth);
        for(int row=1;row<rings;row++){double phi=StrictMath.PI*row/rings,s=StrictMath.sin(phi),y=StrictMath.cos(phi);for(int col=0;col<=segments;col++){double angle=col==segments?0:2*StrictMath.PI*col/segments,x=s*StrictMath.cos(angle),z=s*StrictMath.sin(angle);vertex(v,center,r*x,r*y,r*z,(double)col/segments,(double)row/rings,x,y,z,smooth);}}
        int bottom=v.size();vertex(v,center,0,-r,0,.5,1,0,-1,0,smooth);
        for(int col=0;col<segments;col++)t.add(new Triangle(base,base+1+col+1,base+1+col,color));
        for(int row=0;row<rings-2;row++)for(int col=0;col<segments;col++){int a=base+1+row*(segments+1)+col,b=a+1,c=a+segments+1,d=c+1;t.add(new Triangle(a,b,c,color));t.add(new Triangle(b,d,c,color));}
        int last=base+1+(rings-2)*(segments+1);for(int col=0;col<segments;col++)t.add(new Triangle(bottom,last+col,last+col+1,color));
    }
    private static double[] rotate(String axis,double x,double y,double z){return switch(axis){case "x"->new double[]{y,-x,z};case "z"->new double[]{x,-z,y};default->new double[]{x,y,z};};}
    private static void torus(List<Vertex> v,List<Triangle> t,double[] center,double major,double minor,int segments,int tube,String axis,int color,boolean smooth){
        int base=v.size();
        for(int row=0;row<=segments;row++){double a=row==segments?0:2*StrictMath.PI*row/segments,ca=StrictMath.cos(a),sa=StrictMath.sin(a);for(int col=0;col<=tube;col++){double b=col==tube?0:2*StrictMath.PI*col/tube,cb=StrictMath.cos(b),sb=StrictMath.sin(b);double[] p=rotate(axis,(major+minor*cb)*ca,minor*sb,(major+minor*cb)*sa),n=rotate(axis,cb*ca,sb,cb*sa);vertex(v,center,p[0],p[1],p[2],(double)row/segments,(double)col/tube,n[0],n[1],n[2],smooth);}}
        for(int row=0;row<segments;row++)for(int col=0;col<tube;col++){int a=base+row*(tube+1)+col,b=a+tube+1,c=a+1,d=b+1;t.add(new Triangle(a,c,b,color));t.add(new Triangle(c,d,b,color));}
    }
}
