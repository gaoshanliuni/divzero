package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InterfaceExpressionTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static JsonNode expr(String source)throws Exception{return JSON.readTree(source);}
    @Test void shopSearchAndPriceReactToDataWithoutScriptEvaluation()throws Exception{
        var search=expr("""
        {"op":"contains","args":["Oak planks",{"data":"query"}]}
        """);
        assertTrue(InterfaceExpression.evaluate(search,Map.of("query",TextNode.valueOf("OAK"))).booleanValue());
        assertFalse(InterfaceExpression.evaluate(search,Map.of("query",TextNode.valueOf("stone"))).booleanValue());
        var price=expr("""
        {"op":"mul","args":[{"data":"count"},5]}
        """);
        assertEquals(15,InterfaceExpression.evaluate(price,Map.of("count",IntNode.valueOf(3))).intValue());
    }
    @Test void joinPreservesLeadingAndRepeatedEmptyElements()throws Exception{
        assertEquals(",a,,",InterfaceExpression.evaluate(expr("""
        {"op":"join","args":[{"literal":["","a","",""]},","]}
        """),Map.of()).textValue());
    }
    @Test void conditionalAndLogicalBranchesAreLazy()throws Exception{
        var division="{\"op\":\"div\",\"args\":[1,0]}";
        assertEquals(7,InterfaceExpression.evaluate(expr("{\"op\":\"if\",\"args\":[true,7,"+division+"]}"),Map.of()).intValue());
        assertFalse(InterfaceExpression.evaluate(expr("{\"op\":\"and\",\"args\":[false,"+division+"]}"),Map.of()).booleanValue());
        assertThrows(IllegalArgumentException.class,()->InterfaceExpression.evaluate(expr(division),Map.of()));
    }
    @Test void rejectsExecutableNamesTypeConfusionAndOversizedResults()throws Exception{
        for(String source:List.of("{\"op\":\"eval\",\"args\":[\"Java.loadClass\"]}","{\"op\":\"not\",\"args\":[1]}","{\"op\":\"at\",\"args\":[{\"literal\":[1]},-1]}"))
            assertThrows(IllegalArgumentException.class,()->InterfaceExpression.evaluate(expr(source),Map.of()));
        var concat=expr("""
        {"op":"concat","args":[{"data":"long"},{"data":"long"}]}
        """);
        assertThrows(IllegalArgumentException.class,()->InterfaceExpression.evaluate(concat,Map.of("long",TextNode.valueOf("a".repeat(9000)))));
    }
    @Test void hiddenAncestorBlocksInteractionWithoutEvaluatingUnrelatedBranches()throws Exception{
        var source=JSON.readTree(InterfaceSessionTest.SOURCE);((ObjectNode)source.path("root")).set("bindings",expr("""
        {"visible":{"data":"shown"}}
        """));
        var definition=InterfaceDefinition.parse(source.toString());
        assertFalse(definition.interactiveNode("buy",Map.of("shown",BooleanNode.FALSE)));
        assertTrue(definition.interactiveNode("buy",Map.of("shown",BooleanNode.TRUE)));
    }
}
