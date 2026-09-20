package dev.mineagent.runtime.agent.ui;
import com.fasterxml.jackson.databind.*;
import java.util.*;
/** Literal, bounded observation predicates. No selector scripts, regex evaluation or model-defined authority. */
public final class UiCondition {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> TEXT=Set.of("elementRef","dataAiId","role","labelEquals","valueEquals","textIncludes","documentId");
    private final JsonNode spec;
    private UiCondition(JsonNode spec){this.spec=spec;}
    public static UiCondition parse(String source){
        try{
            if(source==null||source.length()>4096)throw new IllegalArgumentException("UI_CONDITION_BUDGET");
            var root=JSON.readTree(source);if(!root.isObject()||root.isEmpty()||root.size()>9)throw new IllegalArgumentException("UI_CONDITION");
            for(var e:root.properties()){
                if(TEXT.contains(e.getKey())){if(!e.getValue().isTextual()||e.getValue().asText().length()>2048)throw new IllegalArgumentException("UI_CONDITION_TEXT");}
                else if(Set.of("visible","disabled").contains(e.getKey())){if(!e.getValue().isBoolean())throw new IllegalArgumentException("UI_CONDITION_BOOLEAN");}
                else throw new IllegalArgumentException("UI_CONDITION_FIELD");
            }
            return new UiCondition(root);
        }catch(IllegalArgumentException invalid){throw invalid;}catch(Exception invalid){throw new IllegalArgumentException("UI_CONDITION_JSON",invalid);}
    }
    public boolean matches(String observation){
        try{
            if(observation==null||observation.length()>65536)return false;var root=JSON.readTree(observation);
            if(!root.path("status").asText().equals("OBSERVED"))return false;
            if(spec.has("documentId")&&!sameText(root,"documentId",spec.get("documentId")))return false;
            if(spec.has("textIncludes")&&(!root.path("visibleText").isTextual()||!root.path("visibleText").asText().contains(spec.get("textIncludes").asText())))return false;
            if(spec.properties().stream().allMatch(e->Set.of("documentId","textIncludes").contains(e.getKey())))return true;
            for(var element:root.path("elements")){
                if(element.path("secret").asBoolean())continue;boolean matches=true;
                for(String key:List.of("elementRef","dataAiId","role"))if(spec.has(key)&&!sameText(element,key,spec.get(key)))matches=false;
                if(spec.has("labelEquals")&&!sameText(element,"label",spec.get("labelEquals")))matches=false;
                if(spec.has("valueEquals")&&!sameText(element,"value",spec.get("valueEquals")))matches=false;
                for(String key:List.of("visible","disabled"))if(spec.has(key)&&(!element.path(key).isBoolean()||spec.get(key).asBoolean()!=element.path(key).asBoolean()))matches=false;
                if(matches)return true;
            }
            return false;
        }catch(Exception invalid){return false;}
    }
    private static boolean sameText(JsonNode value,String field,JsonNode expected){return value.path(field).isTextual()&&expected.asText().equals(value.path(field).asText());}
}
