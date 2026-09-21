package dev.mineagent.runtime.core.objects;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Discoverable geometric capabilities and offline validation; never creates/activates a package or world object. */
public final class ModelGeometryTools {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ModelGeometryTools(){}
    public static Map<String,Object> capabilities(){return Map.of(
        "versions",List.of(1,2),"primitives",primitives(),
        "minimalModel",minimalModel(),"rootRules","Required root keys: version=2, primitives=[...], collision=[-width/2,0,-depth/2,width/2,height,depth/2]. Do not add id/parts/mesh/shape/kind. Color is a STRING #RRGGBB or #AARRGGBB, not an array or a color name. Required-field names in primitives are literal JSON property names. Omit physics entirely or supply dynamic,mass,gravity,restitution,drag together.",
        "presets",Map.of("sphere_balanced",Map.of("segments",32,"rings",16),"sphere_smooth",Map.of("segments",64,"rings",32)),
        "expandedBudget",Map.of("vertices",RuntimeMesh.MAX_VERTICES,"triangles",RuntimeMesh.MAX_TRIANGLES,"sharedAcrossAllPrimitives",true),
        "item",Map.of("sourceUtf8Bytes",RuntimeItemBinding.MAX_SOURCE,"xAndZ",List.of(-.5,.5),"y",List.of(0,1),"textures","geometry colors only"),
        "coordinates","Block units; all primitives centered at center, not bottom. New primitives support axis=x/y/z (default y), right-handed rotation; plane/disc normal and revolution axis are local +Y. Optional smooth boolean. For item geometry place centerY at half total height; leave padding for decorations.",
        "meshFormat","version=2; primitives is an array; collision stays [xmin,0,zmin,xmax,height,zmax]. Existing boxes and 5-component vertices remain supported; version2 also accepts [x,y,z,u,v,nx,ny,nz].",
        "collision","Default solid AABB. version2 collisionMode=none is a static visual-only world object (never item). Build hollow colliders from separate solid parts around the opening; visual torus alone has no rim collision.");}
    private static Map<String,Object> primitives(){var p=new LinkedHashMap<String,Object>();
        p.put("sphere",Map.of("required",List.of("center","radius","color"),"segments","8..96 default64","rings","4..48 default32"));
        p.put("torus",Map.of("required",List.of("center","majorRadius","minorRadius","color"),"segments","8..96 default64","tubeSegments","4..48 default8"));
        p.put("box",Map.of("required",List.of("center","size","color"),"size","[width,height,depth]; cube uses equal sizes; hard face normals"));
        p.put("plane",Map.of("required",List.of("center","size","color"),"doubleSided","default true","size","[width,depth] in local XZ plane"));
        p.put("disc",Map.of("required",List.of("center","radius","color"),"segments","3..96 default48","doubleSided","default true"));
        p.put("annulus",Map.of("required",List.of("center","innerRadius","outerRadius","color"),"segments","3..96 default48","doubleSided","default true; flat ring, not torus"));
        for(String type:List.of("cylinder","cone"))p.put(type,Map.of("required",List.of("center","radius","height","color"),"segments","3..96 default48","capped","default true"));
        p.put("frustum",Map.of("required",List.of("center","radiusBottom","radiusTop","height","color"),"segments","3..96 default48","capped","default true; either radius may be zero but not both"));
        for(String type:List.of("prism","pyramid"))p.put(type,Map.of("required",List.of("center","radius","height","color"),"sides","3..96 default6; 3=triangular, 4=square","smooth","default false","capped","default true"));
        p.put("ellipsoid",Map.of("required",List.of("center","radii","color"),"segments","8..96 default64","rings","4..48 default32","radii","[xRadius,yRadius,zRadius]"));
        p.put("capsule",Map.of("required",List.of("center","radius","height","color"),"height","total height including hemispheres, >=2*radius","segments","8..96 default48","hemisphereRings","2..24 default8"));
        return Collections.unmodifiableMap(p);
    }
    public static Map<String,Object> minimalModel(){return Map.of("version",2,"primitives",List.of(Map.of("type","box","center",List.of(0,.25,0),"size",List.of(.5,.5,.5),"color","#7cb58c")),"collision",List.of(-.25,0,-.25,.25,.5,.25));}
    /** Structured, bounded repair hints for a rejected read-only tool input. Never runs or rewrites source. */
    public static Map<String,Object> rejection(String source,RuntimeException failure){
        var hints=new ArrayList<String>();try{var root=JSON.readTree(source);
            if(root==null||!root.isObject())hints.add("root must be one JSON object");else{
                if(!root.path("version").isIntegralNumber()||!Set.of(1,2).contains(root.path("version").intValue()))hints.add("/version: integer 2 is required for primitives");
                var allowed=Set.of("version","vertices","triangles","boxes","primitives","collision","collisionMode","physics","texture");root.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)&&hints.size()<16)hints.add("root contains an unsupported property; use only documented root keys");});
                var c=root.path("collision");if(!c.isArray()||c.size()!=6)hints.add("/collision: REQUIRED six-number array [-width/2,0,-depth/2,width/2,height,depth/2], even for static/visual meshes");else if(c.get(1).asDouble(Double.NaN)!=0||Math.abs(c.get(0).asDouble()+c.get(3).asDouble())>1e-6||Math.abs(c.get(2).asDouble()+c.get(5).asDouble())>1e-6)hints.add("/collision: y_min must be 0; X and Z bounds must be symmetric around 0; offsets belong to center or createObject");
                if(root.has("primitives")){if(!root.path("primitives").isArray())hints.add("/primitives: array required");else{int i=0;var catalog=primitives();for(var primitive:root.path("primitives")){String path="/primitives/"+(i++),type=primitive.path("type").asText();if(!catalog.containsKey(type)){hints.add(path+"/type: unknown primitive");continue;}var spec=(Map<?,?>)catalog.get(type);for(var field:(List<?>)spec.get("required"))if(!primitive.has(field.toString())&&hints.size()<16)hints.add(path+"/"+field+": required literal property");if(!primitive.path("color").isTextual()||!primitive.path("color").asText().matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))hints.add(path+"/color: use a string such as #7cb58c");if(hints.size()>=16)break;}}}
                if(root.has("physics"))for(String field:List.of("dynamic","mass","gravity","restitution","drag"))if(!root.path("physics").has(field)&&hints.size()<16)hints.add("/physics/"+field+": supply all physics properties, or omit physics entirely");
            }
        }catch(Exception invalid){hints.add("JSON syntax invalid: supply the model object serialized once as source, without Markdown or trailing text");}
        Throwable cause=failure;for(int i=0;i<6&&cause!=null;i++,cause=cause.getCause()){String code=cause.getMessage();if(code!=null&&code.matches("(?:MESH|OBJECT|RUNTIME_ITEM)_[A-Z_]{1,64}"))hints.add(code);}
        if(hints.isEmpty())hints.add("Check exact property names, numeric bounds and shared expanded vertex/triangle budget against inspect_modeling");
        return Map.of("status","REJECTED","error","RUNTIME_MESH_INVALID","details",hints.stream().limit(16).toList(),"minimalValidModel",minimalModel(),"executed",false);
    }
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
