package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Optional signed package resource; never changes canonical manifest fields or grants UI/world authority. */
public final class UiViewSettings {
    public static final String PATH="ui/view-settings.json";
    private static final Set<String> ANCHORS=Set.of("TOP_LEFT","TOP_RIGHT","BOTTOM_LEFT","BOTTOM_RIGHT","CENTER");
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Settings(String anchor,double width,double height,double offsetX,double offsetY,String appearance,Double opacity){
        public Settings(String anchor,double width,double height,double offsetX,double offsetY,String appearance){this(anchor,width,height,offsetX,offsetY,appearance,null);}
        public Settings{if(opacity!=null&&opacity==0)opacity=0d;if(opacity!=null&&(!Double.isFinite(opacity)||opacity<0||opacity>1))throw new IllegalArgumentException("UI_VIEW_SETTINGS_OPACITY");if(!ANCHORS.contains(anchor)||!Set.of("GLASS_SAGE","PACKAGE").contains(appearance)||!Double.isFinite(width)||!Double.isFinite(height)||width<64||height<64||width>8192||height>8192||!Double.isFinite(offsetX)||!Double.isFinite(offsetY)||Math.abs(offsetX)>32768||Math.abs(offsetY)>32768)throw new IllegalArgumentException("UI_VIEW_SETTINGS_VALUE");}
    }
    private UiViewSettings(){}
    public static Map<String,Settings> parse(String encoded,Set<String> htmlEntries){
        try{
            if(encoded==null||encoded.getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("UI_VIEW_SETTINGS_BUDGET");
            var root=JSON.readTree(encoded);fields(root,Set.of("schema","entries"));if(!root.path("schema").isIntegralNumber()||!root.path("schema").canConvertToInt()||root.path("schema").asInt()!=1||!root.path("entries").isObject()||root.path("entries").size()>16)throw new IllegalArgumentException("UI_VIEW_SETTINGS_SCHEMA");
            var result=new LinkedHashMap<String,Settings>();for(var entry:root.path("entries").properties()){
                String path=entry.getKey();if(!path.matches("ui/[A-Za-z0-9_@./-]+\\.html")||path.contains("..")||!htmlEntries.contains(path))throw new IllegalArgumentException("UI_VIEW_SETTINGS_ENTRY");
                var value=entry.getValue();fields(value,Set.of("anchor","width","height","offsetX","offsetY","appearance","opacity"));
                if(!value.path("anchor").isTextual()||value.has("appearance")&&!value.path("appearance").isTextual())throw new IllegalArgumentException("UI_VIEW_SETTINGS_VALUE");
                result.put(path,new Settings(value.path("anchor").asText(),number(value,"width",null),number(value,"height",null),number(value,"offsetX",0d),number(value,"offsetY",0d),value.path("appearance").asText("GLASS_SAGE"),value.has("opacity")?number(value,"opacity",null):null));
            }return Map.copyOf(result);
        }catch(Exception e){if(e instanceof IllegalArgumentException v&&v.getMessage()!=null&&v.getMessage().startsWith("UI_VIEW_SETTINGS_"))throw v;throw new IllegalArgumentException("UI_VIEW_SETTINGS_INVALID",e);}
    }
    private static double number(JsonNode value,String key,Double fallback){if(!value.has(key)&&fallback!=null)return fallback;var field=value.path(key);if(!field.isNumber())throw new IllegalArgumentException("UI_VIEW_SETTINGS_VALUE");return field.doubleValue();}
    private static void fields(JsonNode value,Set<String> allowed){if(value==null||!value.isObject()||!allowed.containsAll(value.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("UI_VIEW_SETTINGS_FIELDS");}
    public static String layoutKey(UUID packageId,String entry,String target,boolean candidate){try{return "package:"+RuntimePackageCanonicalizer.sha256(JSON.writeValueAsBytes(List.of(packageId,entry,target,candidate)));}catch(Exception e){throw new IllegalArgumentException("UI_VIEW_SETTINGS_SCOPE",e);}}
}
