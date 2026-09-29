package dev.mineagent.runtime.core.conversation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolArgumentsTest {
    @Test void preservesEmbeddedCodeAndAcceptsTransportWrappers(){
        var plain=ToolArguments.parse("set_native_ui","{\"source\":\"x => { return `a`; }\"}");
        assertEquals("x => { return `a`; }",plain.get("source").asText());
        assertEquals(3,ToolArguments.parse("apply_building","```json\n{\"revision\":3}\n```").path("revision").asInt());
        assertEquals(3,ToolArguments.parse("apply_building","```JSON\r\n{\"revision\":3}\r\n```").path("revision").asInt());
        assertEquals(3,ToolArguments.parse("apply_building","\"{\\\"revision\\\":3}\"").path("revision").asInt());
        assertTrue(ToolArguments.parse("plan_building","{\"source\":{\"id\":\"house\"}}").get("source").isTextual());
    }
    @Test void ambiguousOrExecutableInputStillFails(){
        for(String value:new String[]{"[]","null","{\"revision\":1,\"revision\":2}","{} {}","{revision:1}","return {}","```json\n{}"})
            assertThrows(IllegalArgumentException.class,()->ToolArguments.parse("apply_building",value));
    }
}
