package dev.mineagent.runtime.core.objects;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Discoverable geometric capabilities and offline validation; never creates/activates a package or world object. */
public final class ModelGeometryTools {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ModelGeometryTools(){}
    public static Map<String,Object> capabilities(){return Map.of(
        "versions",List.of(1,2),"primitives",Map.of(
            "sphere",Map.of("required",List.of("type","center","radius","color"),"segments","8..96, default64","rings","4..48, default32","smooth","boolean, defaulttrue"),
            "torus",Map.of("required",List.of("type","center","majorRadius","minorRadius","color"),"segments","8..96, default64","tubeSegments","4..48, default8","axis","x/y/z, defaulty; right-handed rotation","smooth","boolean, defaulttrue")),
        "presets",Map.of("sphere_balanced",Map.of("segments",32,"rings",16),"sphere_smooth",Map.of("segments",64,"rings",32)),
        "expandedBudget",Map.of("vertices",RuntimeMesh.MAX_VERTICES,"triangles",RuntimeMesh.MAX_TRIANGLES,"sharedAcrossAllPrimitives",true),
        "item",Map.of("sourceUtf8Bytes",RuntimeItemBinding.MAX_SOURCE,"xAndZ",List.of(-.5,.5),"y",List.of(0,1),"textures","geometry colors only"),
        "coordinates","block units; spheres are centered at center; leave Y padding for surface decorations",
        "meshFormat","version=2; primitives is an array; collision stays [xmin,0,zmin,xmax,height,zmax]. Existing boxes and 5-component vertices remain supported; version2 also accepts [x,y,z,u,v,nx,ny,nz].",
        "collision","Default solid AABB. version2 collisionMode=none is a static visual-only world object (never item). Build hollow colliders from separate solid parts around the opening; visual torus alone has no rim collision.");}
    public static Map<String,Object> inspect(String source,String target){
        if(source==null||source.length()>8192||!Set.of("item","world").contains(target))throw new IllegalArgumentException("MODEL_INSPECTION_INPUT");
        var mesh=RuntimeMesh.parse(source);
        if(target.equals("item"))RuntimeItemBinding.create(new UUID(0,0),new UUID(0,0),"inspection","0".repeat(64),"model.json",source);
        var min=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};var max=new double[]{Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var v:mesh.vertices()){double[] p={v.x(),v.y(),v.z()};for(int i=0;i<3;i++){min[i]=Math.min(min[i],p[i]);max[i]=Math.max(max[i],p[i]);}}
        var spheres=new ArrayList<Map<String,Object>>();
        try{for(var p:JSON.readTree(source).path("primitives"))if(p.path("type").asText().equals("sphere")){var v=new ArrayList<RuntimeMesh.Vertex>();var t=new ArrayList<RuntimeMesh.Triangle>();ParametricMeshes.append(p,v,t);spheres.add(sphereQuality(v,t,p.path("center").get(0).doubleValue(),p.path("center").get(1).doubleValue(),p.path("center").get(2).doubleValue(),p.path("radius").doubleValue()));}}catch(java.io.IOException e){throw new IllegalArgumentException("MODEL_JSON_INVALID",e);}
        var result=new LinkedHashMap<String,Object>();result.put("status","GEOMETRY_VALIDATED_NOT_EXECUTED");result.put("target",target);result.put("sourceUtf8Bytes",source.getBytes(StandardCharsets.UTF_8).length);result.put("expandedVertices",mesh.vertices().size());result.put("expandedTriangles",mesh.triangles().size());result.put("smoothVertices",mesh.vertices().stream().filter(RuntimeMesh.Vertex::hasNormal).count());result.put("bounds",Map.of("min",min,"max",max,"size",new double[]{max[0]-min[0],max[1]-min[1],max[2]-min[2]}));result.put("spheres",spheres);result.put("collision",mesh.collision().nonSolid()?"NONE_VISUAL_ONLY":"AXIS_ALIGNED_BOX_NOT_RENDER_MESH");var c=mesh.collision();boolean inside=min[0]>=-c.width()/2-1e-6&&max[0]<=c.width()/2+1e-6&&min[1]>=-1e-6&&max[1]<=c.height()+1e-6&&min[2]>=-c.depth()/2-1e-6&&max[2]<=c.depth()/2+1e-6;result.put("collisionContainsGeometry",inside);result.put("warnings",inside?List.of():List.of("GEOMETRY_EXTENDS_BEYOND_COLLISION: render shape is valid but collision may be offset or too small; review alignment before use"));return result;
    }
    static Map<String,Object> sphereQuality(List<RuntimeMesh.Vertex> v,List<RuntimeMesh.Triangle> t,double cx,double cy,double cz,double r){
        double vertexError=0,faceInset=0;
        for(var p:v){double x=p.x()-cx,y=p.y()-cy,z=p.z()-cz;vertexError=Math.max(vertexError,Math.abs(Math.sqrt(x*x+y*y+z*z)-r));}
        for(var tri:t){var a=v.get(tri.a());var b=v.get(tri.b());var c=v.get(tri.c());double x=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),y=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),z=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());double n=Math.sqrt(x*x+y*y+z*z);double plane=(x*(a.x()-cx)+y*(a.y()-cy)+z*(a.z()-cz))/n;if(!(plane>0))throw new IllegalArgumentException("SPHERE_WINDING_INVALID");faceInset=Math.max(faceInset,Math.max(0,1-plane/r));}
        return Map.of("radius",r,"vertices",v.size(),"triangles",t.size(),"maxVertexRadiusError",vertexError,"maxFaceInsetRelativeToRadius",faceInset,"outwardWinding",true);
    }
}
