package dev.mineagent.runtime.agent.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UiPresentationVerificationTest {
    @Test void opacityNeedsNativePaintInTheReceiptAndInTheSameViewFreshObservation()throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var proof=new UiPresentationVerification();
        String op="00000000-0000-0000-0000-000000000001";
        String action="{\"action\":\"present\",\"operationId\":\""+op+"\",\"expectedLayoutRevision\":3,\"placement\":{\"anchor\":\"TOP_RIGHT\",\"width\":560,\"height\":440,\"offsetX\":-24,\"offsetY\":12,\"opacity\":0}}";
        var receipt=json.createObjectNode().put("status","APPLIED_HOST").put("executionMode","HOST_PRESENTATION").put("operationId",op).put("documentId","doc").put("afterLayoutRevision",4).put("persisted",true);
        var observation=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(observation(604));var host=(com.fasterxml.jackson.databind.node.ObjectNode)observation.path("hostPresentation");host.put("opacity",0);
        var paint=json.createObjectNode().put("status","NATIVE_ATLAS_PAINTED").put("hostDocumentId",op).put("viewId","view-a").put("token","112233445566778899aabbcc").put("paintSequence",9).put("opacity",0).put("alpha",0);
        host.set("opacityPaint",paint.deepCopy());proof.action(action,receipt.toString());assertFalse(proof.matches(observation.toString()));
        receipt.set("opacityPaint",paint);proof.action(action,receipt.toString());assertTrue(proof.matches(observation.toString()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)host.path("opacityPaint")).put("viewId","other");assertFalse(proof.matches(observation.toString()));
        host.set("opacityPaint",paint.deepCopy());host.put("opacity",.84);assertFalse(proof.matches(observation.toString()));
    }
    private static String observation(double x){return "{\"status\":\"OBSERVED\",\"documentId\":\"doc\",\"visibleText\":\"PRIVATE_BODY_CANARY\",\"elements\":[{}],\"hostPresentation\":{\"canPresent\":true,\"revision\":4,\"source\":\"AGENT\",\"visible\":true,\"area\":{\"x\":12,\"y\":80,\"width\":1176,\"height\":700},\"bounds\":{\"x\":"+x+",\"y\":92,\"width\":560,\"height\":440}}}";}
    @Test void presentationNeedsAnAppliedHostReceiptAndFreshMatchingSameDocument(){
        var proof=new UiPresentationVerification();assertFalse(proof.matches(observation(604)));
        String action="{\"action\":\"present\",\"operationId\":\"00000000-0000-0000-0000-000000000001\",\"expectedLayoutRevision\":3,\"placement\":{\"anchor\":\"TOP_RIGHT\",\"width\":560,\"height\":440,\"offsetX\":-24,\"offsetY\":12}}";
        proof.action(action,"{\"status\":\"APPLIED_DOM\"}");assertFalse(proof.matches(observation(604)));
        proof.action(action,"{\"status\":\"APPLIED_HOST\",\"executionMode\":\"HOST_PRESENTATION\",\"operationId\":\"00000000-0000-0000-0000-000000000001\",\"documentId\":\"doc\",\"afterLayoutRevision\":4,\"persisted\":true}");
        assertTrue(proof.matches(observation(604)));assertFalse(proof.matches(observation(600)));assertFalse(proof.matches(observation(604).replace("doc","other")));
        String sanitized=UiPresentationVerification.sanitize(observation(604));assertFalse(sanitized.contains("PRIVATE_BODY_CANARY"));assertFalse(sanitized.contains("elements"));assertTrue(sanitized.contains("hostPresentation"));
    }
}
