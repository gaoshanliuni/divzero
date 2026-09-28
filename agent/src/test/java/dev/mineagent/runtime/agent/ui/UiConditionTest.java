package dev.mineagent.runtime.agent.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UiConditionTest {
    @Test void missingOrWronglyTypedFieldsDoNotSatisfyFalseOrEmptyConditions(){
        for(String condition:java.util.List.of("{\"disabled\":false}","{\"valueEquals\":\"\"}","{\"dataAiId\":\"\"}","{\"visible\":false}"))
            assertFalse(UiCondition.parse(condition).matches("{\"status\":\"OBSERVED\",\"documentId\":\"d\",\"elements\":[{}]}"),condition);
        assertFalse(UiCondition.parse("{\"disabled\":false}").matches("{\"status\":\"OBSERVED\",\"documentId\":\"d\",\"elements\":[{\"disabled\":\"false\"}]}"));
    }
    final String observation="""
        {"status":"OBSERVED","documentId":"doc","visibleText":"saved result","elements":[{"elementRef":"e1","dataAiId":"title","role":"heading","label":"Saved","value":"","visible":true,"disabled":false,"secret":false}]}
        """;
    @Test void evaluatesTypedPredicatesAgainstObservedStateWithoutExecutingCode(){
        assertTrue(UiCondition.parse("{\"dataAiId\":\"title\",\"labelEquals\":\"Saved\",\"visible\":true}").matches(observation));
        assertTrue(UiCondition.parse("{\"textIncludes\":\"saved result\"}").matches(observation));
        assertFalse(UiCondition.parse("{\"elementRef\":\"e1\",\"valueEquals\":\"fake\"}").matches(observation));
        assertThrows(IllegalArgumentException.class,()->UiCondition.parse("{\"eval\":\"true\"}"));
        assertThrows(IllegalArgumentException.class,()->UiCondition.parse("{}"));
    }
    @Test void refusesSecretAndUnobservedState(){
        assertFalse(UiCondition.parse("{\"role\":\"heading\"}").matches(observation.replace("\"secret\":false","\"secret\":true")));
        assertFalse(UiCondition.parse("{\"textIncludes\":\"saved\"}").matches("{\"status\":\"VIEW_NOT_RENDERED\"}"));
    }
}
