package dev.mineagent.runtime.agent.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.ui.UiPresentationAction;
/** Host-layout evidence is not DOM or world-business success. No page body is needed for layout-only tasks. */
public final class UiPresentationVerification {
    private static final ObjectMapper JSON=new ObjectMapper();private String document;private UiPresentationAction requested;private long after;private String paintDocument,paintView;
    public static String sanitize(String text){try{var raw=JSON.readTree(text);var out=JSON.createObjectNode();for(String key:java.util.List.of("status","documentId","hostPresentation"))if(raw.has(key))out.set(key,raw.get(key));return out.toString();}catch(Exception e){throw new IllegalArgumentException("UI_PRESENTATION_OBSERVATION",e);}}
    public void action(String action,String receipt){try{var raw=JSON.readTree(action);if(!raw.path("action").asText().equals("present"))return;var parsed=UiPresentationAction.parse(action);var r=JSON.readTree(receipt);
        if(!r.path("status").asText().equals("APPLIED_HOST")||!r.path("executionMode").asText().equals("HOST_PRESENTATION")||!r.path("persisted").asBoolean()||!r.path("operationId").asText().equals(parsed.operationId().toString())||r.path("documentId").asText().isBlank()||r.path("afterLayoutRevision").asLong()<parsed.expectedLayoutRevision())return;
        if(parsed.placement().opacity()!=null){if(!validPaint(r.path("opacityPaint"),parsed.placement().opacity()))return;paintDocument=r.path("opacityPaint").path("hostDocumentId").asText();paintView=r.path("opacityPaint").path("viewId").asText();}
        if(document!=null&&!document.equals(r.path("documentId").asText()))return;document=r.path("documentId").asText();requested=parsed;after=r.path("afterLayoutRevision").asLong();
    }catch(Exception e){throw new IllegalArgumentException("UI_PRESENTATION_RECEIPT",e);}}
    public boolean matches(String observation){try{var value=JSON.readTree(observation);if(!value.path("status").asText().equals("OBSERVED"))return false;String id=value.path("documentId").asText();if(id.isBlank())return false;if(document==null)document=id;if(!id.equals(document)||requested==null)return false;
        var p=value.path("hostPresentation");if(!p.path("visible").asBoolean()||!p.path("source").asText().equals("AGENT")||p.path("revision").asLong()<after)return false;
        if(requested.placement().opacity()!=null){double alpha=requested.placement().opacity();var paint=p.path("opacityPaint");if(!p.path("opacity").isNumber()||Double.compare(p.path("opacity").asDouble(),alpha)!=0||!validPaint(paint,alpha)||!paintDocument.equals(paint.path("hostDocumentId").asText())||!paintView.equals(paint.path("viewId").asText()))return false;}
        var area=JSON.treeToValue(p.path("area"),UiPresentationAction.Bounds.class);var actual=JSON.treeToValue(p.path("bounds"),UiPresentationAction.Bounds.class);var expected=requested.placement().resolve(area);
        return Math.abs(actual.x()-expected.x())<=1&&Math.abs(actual.y()-expected.y())<=1&&Math.abs(actual.width()-expected.width())<=1&&Math.abs(actual.height()-expected.height())<=1;
    }catch(Exception ignored){return false;}}
    private static boolean validPaint(JsonNode paint,double alpha){
        try{java.util.UUID.fromString(paint.path("hostDocumentId").asText());return paint.path("status").asText().equals("NATIVE_ATLAS_PAINTED")&&paint.path("token").asText().matches("[a-f0-9]{24}")&&!paint.path("token").asText().equals("0".repeat(24))&&!paint.path("viewId").asText().isBlank()&&paint.path("paintSequence").asLong()>0&&paint.path("opacity").isNumber()&&Double.compare(paint.path("opacity").asDouble(),alpha)==0&&paint.path("alpha").asLong(-1)==Math.round(alpha*255);}catch(Exception bad){return false;}
    }

}
