package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FairTickBudgetTest {
    @Test void laterObjectsReceiveTurnsUnderAnOverloadedTickBudget(){
        var budget=new FairTickBudget();var ids=new ArrayList<UUID>();for(int i=0;i<40;i++)ids.add(UUID.randomUUID());var served=new HashSet<UUID>();
        for(int tick=0;tick<20;tick++)for(var id:ids)if(budget.acquire(id,tick,2,1000)){served.add(id);budget.charge(100);}
        assertEquals(40,served.size());assertEquals(40L,budget.view().get("totalGranted"));
    }
    @Test void spentWorkAndZeroBudgetDeferWithoutDeletingTheQueue(){
        var budget=new FairTickBudget();var first=UUID.randomUUID();var second=UUID.randomUUID();
        assertTrue(budget.acquire(first,1,8,100));budget.charge(100);assertFalse(budget.acquire(second,1,8,100));
        assertFalse(budget.acquire(second,2,0,100));assertTrue(budget.acquire(second,3,8,100));
    }
}
