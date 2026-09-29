package dev.mineagent.runtime.agent.body;

import dev.mineagent.runtime.api.agent.BodyDomain;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorArbiterTest {
    @Test
    void rejectsEqualPriorityTaskCompetingForMovement() {
        var arbiter = new BehaviorArbiter();
        UUID follow = UUID.randomUUID();
        UUID build = UUID.randomUUID();

        assertTrue(arbiter.acquire(follow, Set.of(BodyDomain.MOVEMENT), 10).acquired());
        assertFalse(arbiter.acquire(build, Set.of(BodyDomain.MOVEMENT), 10).acquired());
        assertEquals(follow, arbiter.ownerOf(BodyDomain.MOVEMENT).orElseThrow());
    }

    @Test
    void higherPrioritySafetyTaskPreemptsConflictingLease() {
        var arbiter = new BehaviorArbiter();
        UUID build = UUID.randomUUID();
        UUID evade = UUID.randomUUID();
        arbiter.acquire(build, Set.of(BodyDomain.MOVEMENT, BodyDomain.LOOK), 10);

        AcquireResult result = arbiter.acquire(evade, Set.of(BodyDomain.MOVEMENT), 100);

        assertTrue(result.acquired());
        assertTrue(result.preemptedTaskIds().contains(build));
        assertEquals(evade, arbiter.ownerOf(BodyDomain.MOVEMENT).orElseThrow());
        assertTrue(arbiter.ownerOf(BodyDomain.LOOK).isEmpty());
    }

    @Test
    void releasingTaskFreesEveryOwnedDomain() {
        var arbiter = new BehaviorArbiter();
        UUID task = UUID.randomUUID();
        arbiter.acquire(task, Set.of(BodyDomain.MOVEMENT, BodyDomain.MAIN_HAND), 5);

        arbiter.release(task);

        assertTrue(arbiter.ownerOf(BodyDomain.MOVEMENT).isEmpty());
        assertTrue(arbiter.ownerOf(BodyDomain.MAIN_HAND).isEmpty());
    }
}
