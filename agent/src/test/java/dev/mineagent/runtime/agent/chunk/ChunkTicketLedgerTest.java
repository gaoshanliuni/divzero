package dev.mineagent.runtime.agent.chunk;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkTicketLedgerTest {
    @Test void fairPressureGivesCentersBeforeRadiusAndRotatesOverflow(){
        var ledger=new ChunkTicketLedger(2,100);var a=new UUID(0,1);var b=new UUID(0,2);var c=new UUID(0,3);
        var centers=java.util.Map.of(a,new ChunkCoordinate("overworld",0,0),b,new ChunkCoordinate("overworld",20,0),c,new ChunkCoordinate("overworld",40,0));
        ledger.reconcileFair(centers,2,3,0);for(var id:centers.keySet())assertEquals(java.util.Set.of(centers.get(id)),ledger.chunksFor(id));
        ledger.reconcileFair(centers,2,1,0);assertTrue(ledger.chunksFor(a).contains(centers.get(a)));
        ledger.reconcileFair(centers,2,1,1);assertTrue(ledger.chunksFor(b).contains(centers.get(b)));assertEquals(1,ledger.uniqueTicketCount());
        ledger.reconcileFair(centers,1_875_000,0,0);assertEquals(0,ledger.uniqueTicketCount());
    }
    @Test void configurableRadiusAndBudgetCanExceedPreviousLimits(){
        var ledger=new ChunkTicketLedger(4,200);
        assertTrue(ledger.update(UUID.randomUUID(),"overworld",0,0).accepted());
        assertTrue(ledger.update(UUID.randomUUID(),"overworld",20,0).accepted());
        assertEquals(162,ledger.uniqueTicketCount());
    }
    @Test void hugeRadiusWithSmallBudgetDoesNotAllocateTheFootprint(){
        var ledger=new ChunkTicketLedger(1_875_000,100);
        assertFalse(ledger.update(UUID.randomUUID(),"overworld",0,0).accepted());
        assertEquals(0,ledger.uniqueTicketCount());
    }
    @Test
    void grantsRadiusTwoForFourAgentsWithoutExceedingOneHundredUniqueChunks() {
        var ledger = new ChunkTicketLedger(2, 100);

        for (int index = 0; index < 4; index++) {
            var delta = ledger.update(UUID.randomUUID(), "overworld", index * 10, 0);
            assertTrue(delta.accepted());
        }

        assertEquals(100, ledger.uniqueTicketCount());
    }

    @Test
    void overlapUsesReferenceCountsAndMovingReleasesOnlyUnsharedChunks() {
        var ledger = new ChunkTicketLedger(1, 100);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledger.update(first, "overworld", 0, 0);
        ledger.update(second, "overworld", 0, 0);

        var moved = ledger.update(first, "overworld", 3, 0);

        assertEquals(9, moved.added().size());
        assertTrue(moved.removed().isEmpty());
        assertEquals(18, ledger.uniqueTicketCount());
        var removed = ledger.remove(second);
        assertEquals(9, removed.removed().size());
        assertEquals(9, ledger.uniqueTicketCount());
    }

    @Test
    void rejectsMoveThatWouldExceedGlobalBudgetWithoutChangingExistingLease() {
        var ledger = new ChunkTicketLedger(1, 9);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledger.update(first, "overworld", 0, 0);

        var rejected = ledger.update(second, "overworld", 10, 10);

        assertFalse(rejected.accepted());
        assertEquals("GLOBAL_TICKET_LIMIT", rejected.errorCode());
        assertEquals(9, ledger.uniqueTicketCount());
        assertTrue(ledger.chunksFor(second).isEmpty());
    }
}
