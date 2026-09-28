package dev.mineagent.runtime.core.ui.dynamic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeUiEventStoreTest {
    @TempDir Path dir;
    @Test void restartAndDuplicateCannotDispatchTheSameEffectTwice()throws Exception{
        var scope=new NativeUiStore.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var id=UUID.randomUUID();var path=dir.resolve("events.db");
        try(var store=new NativeUiEventStore(path)){assertTrue(store.begin(scope,"shop",1,id,"buy","purchase","give_item","{}").dispatch());}
        try(var store=new NativeUiEventStore(path)){var uncertain=store.begin(scope,"shop",1,id,"buy","purchase","give_item","{}");assertFalse(uncertain.dispatch());assertEquals("DISPATCHING",uncertain.event().state());store.complete(uncertain.event(),"{\"status\":\"APPLIED\",\"inserted\":1}");}
        try(var store=new NativeUiEventStore(path)){var duplicate=store.begin(scope,"shop",1,id,"buy","purchase","give_item","{}");assertFalse(duplicate.dispatch());assertEquals("COMPLETED",duplicate.event().state());assertTrue(duplicate.event().result().contains("APPLIED"));assertThrows(IllegalStateException.class,()->store.begin(scope,"shop",2,id,"buy","purchase","give_item","{}"));assertTrue(store.list(new NativeUiStore.Scope(scope.world(),UUID.randomUUID(),scope.agent()),"shop").isEmpty());}
    }
    @Test void callbacksBindTypedDataAndCannotAcquireHostAccess()throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var handlers=InterfaceHandlers.parse(json.readTree("{\"buy\":{\"tool\":\"give_item\",\"arguments\":{\"item\":\"minecraft:stone\",\"count\":1},\"bindings\":{\"count\":{\"data\":\"quantity\"}}}}"));
        assertEquals(3,handlers.get("buy").resolve(Map.of("quantity",json.readTree("3"))).get("count").asInt());
        assertThrows(IllegalArgumentException.class,()->InterfaceHandlers.parse(json.readTree("{\"run\":{\"tool\":\"python_execute\",\"arguments\":{}}}")));
    }
    @Test void restylingKeepsTheReceiptForAnUnchangedHandler()throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(InterfaceSessionTest.SOURCE);root.set("handlers",json.readTree("{\"purchase\":{\"tool\":\"give_item\",\"arguments\":{\"item\":\"minecraft:stone\",\"count\":1},\"resultKey\":\"receipt\"}}"));
        var scope=new InterfaceSession.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"shop");var session=new InterfaceSession<AutoCloseable>(scope);session.replace(scope,0,root.toString(),(definition,data)->()->{});session.patch(scope,1,1,Map.of("receipt",json.readTree("{\"status\":\"APPLIED\"}")),data->{});
        root.put("stylesheet","#root { padding-all: 8; }");assertTrue(session.replace(scope,1,root.toString(),(definition,data)->{assertEquals("APPLIED",data.get("receipt").path("status").asText());return ()->{};}).applied());
    }
}
