package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.*;
import java.util.*;
public record UiPresentationAction(UUID operationId,long expectedLayoutRevision,Placement placement) {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public UiPresentationAction{Objects.requireNonNull(operationId);Objects.requireNonNull(placement);if(expectedLayoutRevision<1||expectedLayoutRevision>9007199254740991L)throw new IllegalArgumentException("UI_PRESENTATION_REVISION");}
    public record Bounds(double x,double y,double width,double height){public Bounds{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||!Double.isFinite(height)||x<0||y<0||width<=0||height<=0||x>32768||y>32768||width>32768||height>32768)throw new IllegalArgumentException("UI_PRESENTATION_BOUNDS");}}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Placement(String anchor,double width,double height,double offsetX,double offsetY,Double opacity){
        public Placement(String anchor,double width,double height,double offsetX,double offsetY){this(anchor,width,height,offsetX,offsetY,null);}
        public Placement{if(opacity!=null&&opacity==0)opacity=0d;new UiViewSettings.Settings(anchor,width,height,offsetX,offsetY,"GLASS_SAGE",opacity);}
        public Bounds resolve(Bounds area){double w=Math.min(Math.max(240,width),area.width()),h=Math.min(Math.max(100,height),area.height());double x=area.x()+(anchor.endsWith("RIGHT")?area.width()-w:anchor.equals("CENTER")?(area.width()-w)/2:0)+offsetX;double y=area.y()+(anchor.startsWith("BOTTOM")?area.height()-h:anchor.equals("CENTER")?(area.height()-h)/2:0)+offsetY;return new Bounds(Math.max(area.x(),Math.min(x,area.x()+area.width()-w)),Math.max(area.y(),Math.min(y,area.y()+area.height()-h)),w,h);}
    }
    public static UiPresentationAction parse(String text){try{
        if(text==null||text.length()>8192)throw new IllegalArgumentException("UI_PRESENTATION_INPUT");var root=JSON.readTree(text);fields(root,Set.of("action","operationId","expectedLayoutRevision","placement"));
        if(!root.path("action").asText().equals("present")||!root.path("operationId").isTextual()||!root.path("expectedLayoutRevision").isIntegralNumber()||!root.path("expectedLayoutRevision").canConvertToLong())throw new IllegalArgumentException("UI_PRESENTATION_INPUT");
        var p=root.path("placement");fields(p,Set.of("anchor","width","height","offsetX","offsetY","opacity"));if(!p.path("anchor").isTextual())throw new IllegalArgumentException("UI_PRESENTATION_INPUT");
        return new UiPresentationAction(UUID.fromString(root.path("operationId").asText()),root.path("expectedLayoutRevision").longValue(),new Placement(p.path("anchor").asText(),number(p,"width",null),number(p,"height",null),number(p,"offsetX",0d),number(p,"offsetY",0d),p.has("opacity")?number(p,"opacity",null):null));
    }catch(Exception e){throw new IllegalArgumentException("UI_PRESENTATION_INPUT",e);}}
    private static double number(JsonNode n,String key,Double fallback){if(!n.has(key)&&fallback!=null)return fallback;if(!n.path(key).isNumber())throw new IllegalArgumentException("UI_PRESENTATION_NUMBER");return n.path(key).doubleValue();}
    private static void fields(JsonNode n,Set<String> allowed){if(n==null||!n.isObject()||!allowed.containsAll(n.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("UI_PRESENTATION_FIELDS");}
}
