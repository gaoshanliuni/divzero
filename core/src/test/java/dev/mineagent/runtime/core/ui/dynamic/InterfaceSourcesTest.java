package dev.mineagent.runtime.core.ui.dynamic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InterfaceSourcesTest {
    @Test void sourceDeclarationsRemainDataOnly(){
        String source=InterfaceSessionTest.SOURCE.replace("\"data\":{","\"sources\":{\"balance\":{\"kind\":\"score\",\"objective\":\"coins\",\"holder\":\"$viewer\"}},\"data\":{");
        var definition=InterfaceDefinition.parse(source);assertEquals("coins",definition.sources().get("balance").objective());assertEquals("$viewer",definition.sources().get("balance").holder());
        assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse(source.replace("\"kind\":\"score\"","\"kind\":\"score\",\"command\":\"give @s diamond\"")));
        assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse(source.replace("\"kind\":\"score\"","\"kind\":\"command\"")));
        var scope=new InterfaceSession.Scope(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"shop");
        var session=new InterfaceSession<AutoCloseable>(scope);assertTrue(session.replace(scope,0,source,(d,data)->()->{}).applied());
        assertThrows(IllegalStateException.class,()->session.localData(scope,1,java.util.Map.of("balance",com.fasterxml.jackson.databind.node.IntNode.valueOf(999)),data->{}));
        assertEquals(50,session.data().get("balance").intValue());assertTrue(session.patch(scope,1,1,java.util.Map.of("balance",com.fasterxml.jackson.databind.node.IntNode.valueOf(12)),data->{}).applied());
    }
}
