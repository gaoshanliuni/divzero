package dev.mineagent.runtime.core.interaction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EntityPartReplacementTest {
    @Test void oldAnimationsAndNewReplacementRoundTrip(){
        assertTrue(EntityAnimationSpec.parse("{\"entity_type\":\"minecraft:sheep\"}").replacements().isEmpty());
        var spec=EntityAnimationSpec.parse("""
            {"entity_type":"minecraft:sheep","replacements":{"root/head":{"source_type":"minecraft:wither","source_part":"root/center_head"}}}
            """);
        var head=spec.replacements().get("root/head");assertEquals("minecraft:wither",head.sourceType());assertEquals(java.util.List.of(1d,1d,1d),head.scale());assertEquals(spec,EntityAnimationSpec.parse(spec.source()));assertFalse(spec.blockNative());
    }
    @Test void rejectsBrokenDonorTransformAndNestedReplacement(){
        for(String replacement:java.util.List.of(
            "{\"root/head\":{\"source_type\":\"minecraft:player\",\"source_part\":\"root\"}}",
            "{\"root/head\":{\"source_type\":\"minecraft:wither\",\"source_part\":\"head\"}}",
            "{\"root/head\":{\"source_type\":\"minecraft:wither\",\"source_part\":\"root/center_head\",\"scale\":[0,1,1]}}",
            "{\"root\":{\"source_type\":\"minecraft:wither\",\"source_part\":\"root\"},\"root/head\":{\"source_type\":\"minecraft:wither\",\"source_part\":\"root/center_head\"}}"))
            assertThrows(IllegalArgumentException.class,()->EntityAnimationSpec.parse("{\"entity_type\":\"minecraft:sheep\",\"replacements\":"+replacement+"}"));
    }
    @Test void modelDiscoveryIsReadOnlyAndReplacementIsJournaled(){
        assertTrue(dev.mineagent.runtime.core.conversation.ConversationTools.NAMES.contains("inspect_entity_model"));
        assertFalse(dev.mineagent.runtime.core.conversation.ConversationTools.mutation("inspect_entity_model"));
        assertTrue(dev.mineagent.runtime.core.conversation.ConversationTools.NAMES.contains("replace_entity_part"));
        assertTrue(dev.mineagent.runtime.core.conversation.ConversationTools.mutation("replace_entity_part"));
    }
}
