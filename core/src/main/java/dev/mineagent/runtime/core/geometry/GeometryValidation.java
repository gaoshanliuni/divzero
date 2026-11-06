package dev.mineagent.runtime.core.geometry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Structural diagnostics before rasterization; the rasterizer remains the authority for shape semantics. */
final class GeometryValidation {
    private static final Map<String,Set<String>> SHAPES=Map.ofEntries(
        Map.entry("box",Set.of("min","max","mode","thickness")),Map.entry("slope",Set.of("min","max","axis","ascending","thickness")),
        Map.entry("line",Set.of("points","radius")),Map.entry("curve",Set.of("points","radius")),Map.entry("plane",Set.of("points")),Map.entry("surface",Set.of("points")),
        Map.entry("polygon",Set.of("points","height","mode","thickness","holes","cap_top","cap_bottom")),Map.entry("cylinder",Set.of("center","radius","height","thickness")),
        Map.entry("ellipsoid",Set.of("center","radius","thickness")),Map.entry("dome",Set.of("center","radius","thickness")));
    private static Object actual(JsonNode n){return n.isMissingNode()?"missing":n.isContainerNode()?n.getNodeType()+" ("+n.size()+" entries)":n.toString();}
    private static void need(boolean ok,String code,String path,String expected,JsonNode n){if(!ok)throw new GeometryDiagnostic("GEOMETRY_"+code,path,expected,actual(n));}
    private static void fields(JsonNode n,String path,Set<String> allowed){
        need(n.isObject(),"FIELDS",path,"object",n);
        for(var e:n.properties())need(allowed.contains(e.getKey()),"FIELDS",path+"/"+e.getKey(),"allowed fields: "+String.join(", ",new TreeSet<>(allowed)),e.getValue());
    }
    private static void array(JsonNode n,String p,int min,int max){need(n.isArray()&&n.size()>=min&&n.size()<=max,"VECTOR",p,"array with "+min+".."+max+" elements",n);}
    private static void numeric(JsonNode n,String p,boolean integer,double min,double max){need(n.isNumber()&&(!integer||n.isIntegralNumber())&&Double.isFinite(n.doubleValue())&&n.doubleValue()>=min&&n.doubleValue()<=max,integer?"INTEGER":"NUMBER",p,(integer?"integer":"number")+" in ["+min+", "+max+"]",n);}
    private static void vector(JsonNode n,String p,int size,boolean integer,double min,double max){array(n,p,size,size);for(int i=0;i<size;i++)numeric(n.get(i),p+"/"+i,integer,min,max);}
    private static void text(JsonNode n,String p){need(n.isTextual()&&!n.asText().isBlank()&&n.asText().length()<=1024,"TEXT",p,"nonempty string up to 1024 characters",n);}
    private static void choice(JsonNode n,String p,String... values){need(n.isTextual()&&List.of(values).contains(n.asText()),"ENUM",p,String.join(" | ",values),n);}
    static void validate(JsonNode root){
        if(root==null)throw new GeometryDiagnostic("GEOMETRY_JSON","/","one JSON object","empty input");
        fields(root,"",Set.of("origin","parts","replace"));vector(root.path("origin"),"/origin",3,true,-30000000,30000000);array(root.path("parts"),"/parts",1,64);
        if(root.has("replace")){array(root.get("replace"),"/replace",1,32);for(int i=0;i<root.get("replace").size();i++)text(root.get("replace").get(i),"/replace/"+i);}
        for(int i=0;i<root.get("parts").size();i++)part(root.get("parts").get(i),"/parts/"+i);
    }
    private static void material(JsonNode n,String p){
        if(n.isTextual()){text(n,p);return;}fields(n,p,Set.of("mode","states","axis","width","seed","weights"));
        array(n.path("states"),p+"/states",1,32);for(int i=0;i<n.get("states").size();i++)text(n.get("states").get(i),p+"/states/"+i);
        if(n.has("mode"))choice(n.get("mode"),p+"/mode","checker","stripe","layers","weighted");if(n.has("axis"))choice(n.get("axis"),p+"/axis","x","y","z");
        if(n.has("width"))numeric(n.get("width"),p+"/width",true,1,4096);if(n.has("seed"))numeric(n.get("seed"),p+"/seed",true,Integer.MIN_VALUE,Integer.MAX_VALUE);
        if(n.has("weights")){array(n.get("weights"),p+"/weights",n.get("states").size(),n.get("states").size());for(int i=0;i<n.get("weights").size();i++)numeric(n.get("weights").get(i),p+"/weights/"+i,true,1,1000000);}
    }
    private static void part(JsonNode n,String p){
        String kind=n.path("kind").asText();need(SHAPES.containsKey(kind),"KIND",p+"/kind",String.join(" | ",new TreeSet<>(SHAPES.keySet())),n.path("kind"));
        var allowed=new HashSet<>(SHAPES.get(kind));allowed.addAll(Set.of("kind","material","transforms"));fields(n,p,allowed);material(n.path("material"),p+"/material");
        if(Set.of("box","slope").contains(kind)){vector(n.path("min"),p+"/min",3,true,-4096,4096);vector(n.path("max"),p+"/max",3,true,-4096,4096);for(int i=0;i<3;i++)need(n.get("max").get(i).intValue()>=n.get("min").get(i).intValue(),"BOUNDS",p+"/max/"+i,">= min["+i+"]",n.get("max").get(i));}
        if(Set.of("line","curve","plane","surface","polygon").contains(kind)){
            int min=switch(kind){case "curve","surface"->4;case "plane","polygon"->3;default->2;};int max=Set.of("curve","surface","plane").contains(kind)?min:128;
            array(n.path("points"),p+"/points",min,max);for(int i=0;i<n.get("points").size();i++)vector(n.get("points").get(i),p+"/points/"+i,3,false,-4096,4096);
        }
        if(Set.of("cylinder","ellipsoid","dome").contains(kind)){vector(n.path("center"),p+"/center",3,false,-4096,4096);vector(n.path("radius"),p+"/radius",kind.equals("cylinder")?2:3,false,.5,4096);}
        if(Set.of("cylinder","polygon").contains(kind))numeric(n.path("height"),p+"/height",true,1,4096);
        if(n.has("holes")){array(n.get("holes"),p+"/holes",0,32);for(int h=0;h<n.get("holes").size();h++){var ring=n.get("holes").get(h);array(ring,p+"/holes/"+h,3,128);for(int i=0;i<ring.size();i++)vector(ring.get(i),p+"/holes/"+h+"/"+i,3,false,-4096,4096);}}
        if(n.has("mode"))choice(n.get("mode"),p+"/mode","solid","walls","shell");if(n.has("axis"))choice(n.get("axis"),p+"/axis","x","z");
        if(n.has("thickness"))numeric(n.get("thickness"),p+"/thickness",Set.of("box","polygon","slope").contains(kind),Set.of("box","polygon").contains(kind)?1:0,4096);
        for(String key:List.of("ascending","cap_top","cap_bottom"))if(n.has(key))need(n.get(key).isBoolean(),"BOOLEAN",p+"/"+key,"boolean",n.get(key));
        if(n.has("transforms")){array(n.get("transforms"),p+"/transforms",0,32);for(int i=0;i<n.get("transforms").size();i++)transform(n.get("transforms").get(i),p+"/transforms/"+i);}
    }
    private static void transform(JsonNode n,String p){
        String op=n.path("op").asText();Set<String> allowed=switch(op){case "translate"->Set.of("op","offset");case "rotate"->Set.of("op","turns","pivot");case "mirror"->Set.of("op","axis","pivot");case "array"->Set.of("op","count","step");case "path"->Set.of("op","points","spacing","orient");default->throw new GeometryDiagnostic("GEOMETRY_TRANSFORM",p+"/op","translate | rotate | mirror | array | path",op);};fields(n,p,allowed);
        for(String key:List.of("offset","step","count"))if(allowed.contains(key))vector(n.path(key),p+"/"+key,3,true,key.equals("count")?1:-4096,key.equals("count")?2048:4096);
        if(n.has("pivot"))vector(n.get("pivot"),p+"/pivot",3,true,-4096,4096);if(op.equals("rotate"))numeric(n.path("turns"),p+"/turns",true,-3,3);if(op.equals("mirror"))choice(n.path("axis"),p+"/axis","x","z");
        if(op.equals("path")){array(n.path("points"),p+"/points",2,128);for(int i=0;i<n.get("points").size();i++)vector(n.get("points").get(i),p+"/points/"+i,3,false,-4096,4096);numeric(n.path("spacing"),p+"/spacing",false,.5,4096);if(n.has("orient"))need(n.get("orient").isBoolean(),"BOOLEAN",p+"/orient","boolean",n.get("orient"));}
    }
    private GeometryValidation(){}
}
