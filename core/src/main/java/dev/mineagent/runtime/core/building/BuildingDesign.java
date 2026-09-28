package dev.mineagent.runtime.core.building;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Persistent, addressable construction intent; geometry and world edits have separate lifecycles. */
public final class BuildingDesign {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ObjectNode document;
    private final Map<String,JsonNode> components;
    private BuildingDesign(ObjectNode document,Map<String,JsonNode> components){this.document=document.deepCopy();this.components=Map.copyOf(components);}
    public String id(){return document.path("id").asText();}
    public String name(){return document.path("name").asText();}
    public String dimension(){return document.path("dimension").asText();}
    public boolean clearExisting(){return document.path("clear_existing").asBoolean(false);}
    public String source(){return document.toString();}
    public Set<String> componentIds(){return components.keySet();}
    public JsonNode component(String id){var component=components.get(id);if(component==null)throw bad("COMPONENT_NOT_FOUND");return component.deepCopy();}
    public JsonNode checks(){return document.path("checks").deepCopy();}
    public List<Integer> origin(){return List.of(document.path("origin").get(0).intValue(),document.path("origin").get(1).intValue(),document.path("origin").get(2).intValue());}
    public List<String> order(){var order=new ArrayList<String>();var seen=new HashSet<String>();for(String id:components.keySet().stream().sorted().toList())visit(id,new HashSet<>(),seen,order);return List.copyOf(order);}
    public String geometry(String id){
        JsonNode c=component(id);var out=JSON.createObjectNode();out.set("origin",document.get("origin").deepCopy());var parts=out.putArray("parts");
        JsonNode source=c.has("template")?document.path("templates").path(c.get("template").asText()):c.path("parts");
        for(var part:source){var copy=(ObjectNode)part.deepCopy();if(c.has("material"))copy.set("material",c.get("material").deepCopy());
            if(c.has("transforms")){ArrayNode transforms=copy.has("transforms")?(ArrayNode)copy.get("transforms"):copy.putArray("transforms");for(var transform:c.get("transforms"))transforms.add(transform.deepCopy());}
            parts.add(copy);
        }
        return out.toString();
    }
    public Set<String> changedComponents(BuildingDesign previous){
        if(previous==null)return componentIds();if(!id().equals(previous.id()))throw bad("DESIGN_ID_CHANGED");
        var changed=new TreeSet<String>();var ids=new HashSet<>(componentIds());ids.addAll(previous.componentIds());
        for(String id:ids)if(!dimension().equals(previous.dimension())||!components.containsKey(id)||!previous.components.containsKey(id)||!geometry(id).equals(previous.geometry(id)))changed.add(id);
        return Set.copyOf(changed);
    }
    public static BuildingDesign parse(String source){
        if(source==null||source.length()>131072)throw bad("SOURCE_SIZE");
        try{
            JsonNode n=JSON.readTree(source);fields(n,"id","name","dimension","origin","templates","components","checks","clear_existing");id(n.path("id"));
            if(n.has("clear_existing")&&!n.get("clear_existing").isBoolean())throw bad("CLEAR_EXISTING");
            if(!n.path("dimension").isTextual()||!n.get("dimension").asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw bad("DIMENSION");
            if(!n.path("name").isTextual()||n.get("name").asText().isBlank()||n.get("name").asText().length()>256)throw bad("NAME");vector(n.path("origin"),30_000_000);
            if(n.has("templates")){if(!n.get("templates").isObject()||n.get("templates").size()>128)throw bad("TEMPLATES");for(var e:n.get("templates").properties()){id(TextNode.valueOf(e.getKey()));parts(e.getValue());}}
            JsonNode list=n.path("components");if(!list.isArray()||list.isEmpty()||list.size()>512)throw bad("COMPONENTS");
            var components=new LinkedHashMap<String,JsonNode>();
            for(var c:list){fields(c,"id","name","parts","template","material","transforms","depends_on","parent");String id=id(c.path("id"));if(components.putIfAbsent(id,c.deepCopy())!=null)throw bad("DUPLICATE_COMPONENT");
                if(c.has("parts")==c.has("template"))throw bad("PARTS_OR_TEMPLATE");
                if(c.has("parts"))parts(c.get("parts"));else if(!n.path("templates").has(id(c.get("template"))))throw bad("TEMPLATE_NOT_FOUND");
                if(c.has("transforms")&&(!c.get("transforms").isArray()||c.get("transforms").size()>32))throw bad("TRANSFORMS");
                if(c.has("depends_on")){if(!c.get("depends_on").isArray()||c.get("depends_on").size()>512)throw bad("DEPENDENCIES");for(var dep:c.get("depends_on"))id(dep);}
                if(c.has("parent"))id(c.get("parent"));
            }
            var result=new BuildingDesign((ObjectNode)n,components);result.order();
            if(n.has("checks")){
                if(!n.get("checks").isArray()||n.get("checks").size()>512)throw bad("CHECKS");var ids=new HashSet<String>();
                for(var check:n.get("checks")){
                    fields(check,"id","component","kind","min","max","expected","path","headroom","allow_floating","description");
                    if(!ids.add(id(check.path("id"))))throw bad("DUPLICATE_CHECK");if(!components.containsKey(id(check.path("component"))))throw bad("CHECK_COMPONENT");
                    String kind=check.path("kind").asText();if(!Set.of("states","clearance","path","bounds","support").contains(kind))throw bad("CHECK_KIND");
                    // Detailed block-state/collision validation is performed against the actual world at verification time.
                    if(kind.equals("path")){if(!check.path("path").isArray()||check.get("path").size()<2||check.get("path").size()>4096)throw bad("CHECK_PATH");for(var point:check.get("path"))vector(point,4096);}
                    else{vector(check.path("min"),4096);vector(check.path("max"),4096);for(int i=0;i<3;i++)if(check.get("min").get(i).asInt()>check.get("max").get(i).asInt())throw bad("CHECK_BOUNDS");}
                    if(kind.equals("states")&&(!check.path("expected").isTextual()||check.get("expected").asText().isBlank()))throw bad("CHECK_EXPECTED");
                    if(check.has("headroom")&&(!check.get("headroom").isIntegralNumber()||check.get("headroom").asInt()<1||check.get("headroom").asInt()>16))throw bad("CHECK_HEADROOM");
                    if(check.has("allow_floating")&&!check.get("allow_floating").isBoolean())throw bad("CHECK_FLOATING");
                }
            }
            return result;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw bad("JSON");}
    }
    private void visit(String id,Set<String> visiting,Set<String> seen,List<String> order){
        if(seen.contains(id))return;if(!visiting.add(id))throw bad("DEPENDENCY_CYCLE");JsonNode c=components.get(id);if(c==null)throw bad("DEPENDENCY_NOT_FOUND");
        if(c.has("parent"))visit(c.get("parent").asText(),visiting,seen,order);
        for(var dep:c.path("depends_on"))visit(dep.asText(),visiting,seen,order);
        visiting.remove(id);seen.add(id);order.add(id);
    }
    private static void parts(JsonNode n){if(!n.isArray()||n.isEmpty()||n.size()>64)throw bad("PARTS");for(var part:n)if(!part.isObject()||!part.path("kind").isTextual()||!part.has("material"))throw bad("PART");}
    private static void vector(JsonNode n,int max){if(!n.isArray()||n.size()!=3)throw bad("POSITION");for(var value:n)if(!value.isIntegralNumber()||!value.canConvertToInt()||Math.abs((long)value.intValue())>max)throw bad("POSITION");}
    private static String id(JsonNode n){if(!n.isTextual()||!n.asText().matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw bad("ID");return n.asText();}
    private static void fields(JsonNode n,String... names){if(n==null||!n.isObject()||!Set.of(names).containsAll(n.properties().stream().map(Map.Entry::getKey).toList()))throw bad("FIELDS");}
    private static IllegalArgumentException bad(String code){return new IllegalArgumentException("BUILDING_DESIGN_"+code);}
}
