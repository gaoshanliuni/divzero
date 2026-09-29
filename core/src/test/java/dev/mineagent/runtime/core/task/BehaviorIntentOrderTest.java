package dev.mineagent.runtime.core.task;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorIntentOrderTest {
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
