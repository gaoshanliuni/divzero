package dev.mineagent.runtime.core.task;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorIntentOrderTest {
    @Test void siblingUpdatesKeepOriginalArrivalAndRespectIndependentNewerStops(){
        var order=new BehaviorIntentOrder<String>();var group=UUID.randomUUID();order.accept("a",group,false);
        assertTrue(order.claimRelated("a","b",group));assertTrue(order.claimRelated("a","c",group));
        order.invalidate("b");assertFalse(order.currentRelated("a","b",group));assertFalse(order.claimRelated("a","b",group));assertTrue(order.currentRelated("a","c",group));
        var newer=UUID.randomUUID();order.accept("c",newer,true);assertFalse(order.claimRelated("a","c",group));
        assertTrue(order.current("a",group));order.invalidate("a");assertFalse(order.currentRelated("a","d",group));
    }
    @Test void casualQueuedChatDoesNotCancelPlanningButNewBodyIntentDoes() {
        var order = new BehaviorIntentOrder<String>();
        var first = UUID.randomUUID(); var chat = UUID.randomUUID(); var follow = UUID.randomUUID();
        order.accept("ai", first, false);
        order.accept("ai", chat, false);
        assertTrue(order.claim("ai", first));
        order.accept("ai", follow, true);
        assertFalse(order.current("ai", first));
        assertFalse(order.claim("ai", chat));
        assertTrue(order.claim("ai", follow));
        assertTrue(order.claim("ai", follow));
    }

    @Test void modelRecognizedIntentAndUiStopKeepArrivalOrder() {
        var order = new BehaviorIntentOrder<String>();
        var first = UUID.randomUUID(); var next = UUID.randomUUID();
        order.accept("ai", first, false); order.accept("ai", next, false);
        assertTrue(order.claim("ai", next));
        assertFalse(order.claim("ai", first));
        order.invalidate("ai");
        order.accept("ai", next, true); // Dequeuing never gives an old request a fresh arrival.
        assertFalse(order.claim("ai", next));
        var other = UUID.randomUUID(); order.accept("other", other, true);
        assertTrue(order.claim("other", other));
        assertFalse(order.claim("other", next));
    }
}
