package dev.mineagent.runtime.core.interaction;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class InteractionRuleSpecTest {
 @Test void placementUseAndBreakAreIndependent(){for(String e:java.util.List.of("block_place","block_use","block_break")){var spec=InteractionRuleSpec.parse("{\"position\":[1,64,2],\"events\":[\""+e+"\"]}");assertEquals(java.util.Set.of(e),spec.events());}}

 @Test void doorKeyIsRealCallbackPolicyNotCosmetic(){var rule=InteractionRuleSpec.parse("{\"position\":[1,64,2],\"events\":[\"block_use\",\"block_break\"],\"key_item\":\"minecraft:stick\",\"on_denied\":[{\"type\":\"message\",\"value\":\"locked\"}]}");assertEquals("cancel",rule.decision());assertEquals("minecraft:stick",rule.keyItem());assertEquals(2,rule.events().size());assertEquals("locked",rule.deniedActions().getFirst().get("value"));}
 @Test void ambiguousOrMismatchedTargetsAreRejected(){assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.parse("{\"position\":[1,2,3],\"entity_id\":\"00000000-0000-0000-0000-000000000001\",\"events\":[\"block_use\"]}"));assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.parse("{\"position\":[1,2,3],\"events\":[\"entity_use\"]}"));}
}
