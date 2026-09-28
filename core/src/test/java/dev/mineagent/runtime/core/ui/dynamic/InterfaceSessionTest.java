package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InterfaceSessionTest {
    static final String SOURCE="""
        {"id":"shop","title":"Shop","surface":"SCREEN","data":{"query":"","balance":50},
         "root":{"id":"root","type":"row","children":[
            {"id":"search","type":"input","bind":"query"},
            {"id":"balance","type":"label","bind":"balance"},
            {"id":"buy","type":"button","text":"Buy","events":{"click":[{"op":"emit","action":"purchase","args":{"item":"stone"}}]}}
         ]}}
        """;
    private static class Render implements AutoCloseable {boolean closed;@Override public void close(){closed=true;}}
    private static InterfaceSession.Scope scope(){return new InterfaceSession.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"shop");}
    @Test void candidateFailureKeepsRenderedTreeRevisionAndDraft() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);var first=new Render();
        assertTrue(session.replace(scope,0,SOURCE,(definition,data)->first).applied());
        session.input(scope,1,"search",TextNode.valueOf("oak"));
        var failed=session.replace(scope,1,SOURCE,(definition,data)->{throw new IllegalArgumentException("invalid style at #search");});
        assertFalse(failed.applied());assertEquals(1,session.revision());assertSame(first,session.rendered());assertFalse(first.closed);
        assertEquals("oak",session.data().get("query").asText());assertTrue(failed.error().contains("#search"));
    }
    @Test void hotReplacementPreservesMatchingInputButChangedIdentityResetsIt() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);var old=new Render();
        session.replace(scope,0,SOURCE,(d,data)->old);session.input(scope,1,"search",TextNode.valueOf("oak"));
        assertTrue(session.replace(scope,1,SOURCE.replace("\"type\":\"row\"","\"type\":\"column\""),(d,data)->{assertEquals("oak",data.get("query").asText());return new Render();}).applied());
        assertTrue(old.closed);assertEquals(2,session.revision());
        session.replace(scope,2,SOURCE.replace("\"id\":\"search\"","\"id\":\"replacement-search\""),(d,data)->{assertEquals("",data.get("query").asText());return new Render();});
    }
    @Test void incrementalPatchDoesNotRebuildOrOverwriteUserInput() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);var rendered=new Render();
        session.replace(scope,0,SOURCE,(d,data)->rendered);session.input(scope,1,"search",TextNode.valueOf("oak"));
        assertTrue(session.patch(scope,1,2,Map.of("balance",IntNode.valueOf(40),"query",TextNode.valueOf("server")),data->{assertEquals("oak",data.get("query").asText());}).applied());
        assertSame(rendered,session.rendered());assertEquals(1,session.revision());assertEquals(40,session.data().get("balance").asInt());
        assertThrows(IllegalStateException.class,()->session.patch(scope,1,2,Map.of(),d->{}));
    }
    @Test void oldCallbacksWrongWorldAndDisconnectedSessionAreRejected() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);
        session.replace(scope,0,SOURCE,(d,data)->new Render());session.replace(scope,1,SOURCE,(d,data)->new Render());
        assertThrows(IllegalStateException.class,()->session.actions(scope,1,"buy","click"));
        assertThrows(SecurityException.class,()->session.actions(scope(),2,"buy","click"));
        assertEquals("purchase",session.actions(scope,2,"buy","click").get(0).get("action").asText());
        session.close();assertThrows(IllegalStateException.class,()->session.actions(scope,2,"buy","click"));
    }
    @Test void hudNeedsExplicitInteractionAndHiddenViewsCannotInteract() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);
        session.replace(scope,0,SOURCE.replace("SCREEN","HUD"),(d,data)->new Render());
        assertFalse(session.interactive());assertThrows(IllegalStateException.class,()->session.actions(scope,1,"buy","click"));
        session.interactive(true);assertFalse(session.actions(scope,1,"buy","click").isEmpty());
        session.visible(false);assertThrows(IllegalStateException.class,()->session.input(scope,1,"search",TextNode.valueOf("ignored")));
    }
    @Test void definitionsRejectAmbiguousIdsExecutableFieldsAndExternalResources() {
        for(String invalid:List.of(SOURCE.replace("\"id\":\"search\"","\"id\":\"buy\""),
                SOURCE.replace("\"type\":\"input\"","\"type\":\"input\",\"script\":\"Java.loadClass('java.lang.Runtime')\""),
                SOURCE.replace("\"title\":\"Shop\"","\"title\":\"Shop\",\"stylesheet\":\"@import https://example.org/theme\""),
                SOURCE.replace("\"id\":\"shop\"","\"id\":\"shop\",\"id\":\"other\"")))assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse(invalid));
        assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse(SOURCE+"{}"));
    }
    @Test void reentrantReplacementCannotOverwriteNewerRevision() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);var stale=new Render();
        var result=session.replace(scope,0,SOURCE,(d,data)->{assertTrue(session.replace(scope,0,SOURCE,(inner,values)->new Render()).applied());return stale;});
        assertFalse(result.applied());assertEquals(1,session.revision());assertTrue(stale.closed);
    }
    @Test void definitionAndStateSnapshotsCannotBeMutatedByReaders() {
        var scope=scope();var session=new InterfaceSession<Render>(scope);session.replace(scope,0,SOURCE,(d,data)->new Render());
        ((ObjectNode)session.definition().root()).put("id","hijacked");session.data().put("balance",IntNode.valueOf(-1));
        assertEquals("root",session.definition().root().get("id").asText());assertEquals(50,session.data().get("balance").asInt());
    }
    @Test void hiddenAncestorRejectsEventsAndInputs(){var scope=scope();var session=new InterfaceSession<Render>(scope);session.replace(scope,0,SOURCE.replace("\"id\":\"root\"","\"id\":\"root\",\"visible\":false"),(d,data)->new Render());assertThrows(IllegalStateException.class,()->session.actions(scope,1,"buy","click"));assertThrows(IllegalStateException.class,()->session.input(scope,1,"search",TextNode.valueOf("hidden")));}
    @Test void screenConvertedToHudBecomesPassive(){var scope=scope();var session=new InterfaceSession<Render>(scope);session.replace(scope,0,SOURCE,(d,data)->new Render());assertTrue(session.interactive());session.replace(scope,1,SOURCE.replace("SCREEN","HUD"),(d,data)->new Render());assertFalse(session.interactive());}
    @Test void failedDataUpdateRestoresPreviouslyCommittedBindings(){var scope=scope();var session=new InterfaceSession<Render>(scope);session.replace(scope,0,SOURCE,(d,data)->new Render());var painted=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>(session.data());var result=session.patch(scope,1,1,Map.of("balance",IntNode.valueOf(20)),data->{painted.putAll(data);if(data.get("balance").asInt()==20)throw new IllegalArgumentException("paint failed");});assertFalse(result.applied());assertEquals(50,painted.get("balance").asInt());assertEquals(1,session.dataRevision());}
}
