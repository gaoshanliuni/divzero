package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ToolValidationTest {
    @Test void reportsRouteCoordinatePathWithoutExecutingOrInventingTheMissingCoordinate(){
        var args=ToolArguments.parse("patrol_route","{\"id\":\"patrol\",\"actor\":\"ai\",\"dimension\":\"minecraft:overworld\",\"expected_revision\":0,\"route\":[[0,64,0],[1,64,0],[2,64]]}");
        var result=ToolValidation.check("patrol_route",args);
        assertTrue(result.issues().stream().anyMatch(i->i.field().equals("route[2]")));
        assertEquals(2,result.arguments().path("route").get(2).size());assertEquals("NOT_STARTED",result.rejection().get("executionState"));
    }
    @Test void repairsRepresentationsButPreservesIdsAndDoesNotEvaluateText(){
        var args=ToolArguments.parse("patrol_route","{\"id\":\"001\",\"actor\":\"AI\",\"dimension\":\"minecraft:overworld\",\"expected_revision\":\"0\",\"repeat\":\"true\",\"route\":[[\"0\",\"64\",\"0\"]]}");
        var result=ToolValidation.check("patrol_route",args);
        assertEquals("001",result.arguments().path("id").asText());assertEquals("ai",result.arguments().path("actor").asText());assertTrue(result.arguments().path("repeat").isBoolean());assertTrue(result.arguments().path("route").get(0).get(0).isNumber());
        assertFalse(result.issues().isEmpty()); // ID is invalid, rather than guessed or silently replaced.
        var code=ToolValidation.check("apply_building",ToolArguments.parse("apply_building","{\"id\":\"home\",\"revision\":\"1+2\"}"));assertFalse(code.issues().isEmpty());
    }
    @Test void unknownPropertyInRejectedCandidateIsNotAnUnknownWorldWrite(){
        var result=ToolErrors.explain(java.util.Map.of("status","REJECTED","error","Wrapped: INTERFACE_LSS_UNKNOWN_PROPERTY: widthx at #root (build.js#6)","executionState","CANDIDATE_ONLY","candidate_id","draft","runningVersionPreserved",true));
        assertEquals("CANDIDATE_VALIDATION",result.get("category"));assertEquals("CANDIDATE_ONLY",result.get("executionState"));assertTrue(result.get("suggestedAction").toString().contains("targeted edit"));
        var unknown=ToolErrors.explain(java.util.Map.of("status","UNKNOWN","error","NATIVE_UI_CLIENT_ACK_TIMEOUT","executionState","UNKNOWN"));assertEquals("OUTCOME_UNKNOWN",unknown.get("category"));
        var field=ToolErrors.explain(java.util.Map.of("status","REJECTED","error","UNKNOWN_FIELD","executionState","NOT_STARTED"));assertEquals("VALIDATION",field.get("category"));
    }
}
