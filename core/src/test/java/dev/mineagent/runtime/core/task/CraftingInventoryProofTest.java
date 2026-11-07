package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class CraftingInventoryProofTest {
    @Test void outputAndRemaindersMustActuallyEnterTheActorInventory(){
        var expected=CraftingInventoryProof.expected(Map.of("planks",2),"sticks",4,Map.of("bucket",1));
        assertEquals(Map.of("planks",2,"sticks",4,"bucket",1),expected);
        assertFalse(CraftingInventoryProof.matches(expected,Map.of("planks",2,"sticks",4)));
        assertFalse(CraftingInventoryProof.matches(expected,Map.of("planks",2,"sticks",8,"bucket",1)));
        assertTrue(CraftingInventoryProof.matches(expected,Map.of("planks",2,"sticks",4,"bucket",1)));
    }
}
