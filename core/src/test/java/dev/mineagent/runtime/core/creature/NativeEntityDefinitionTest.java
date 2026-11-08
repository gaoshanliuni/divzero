package dev.mineagent.runtime.core.creature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeEntityDefinitionTest {
    @Test void copyPolicyIsExplicitAndNeverAcceptsOpaqueNbtOrIdentity(){
        var basic=NativeEntityDefinition.parse("{\"name\":\"x\",\"type\":\"minecraft:cow\"}");assertEquals(NativeEntityDefinition.CopyPolicy.BASIC,basic.copyPolicy());
        var safe=NativeEntityDefinition.parse("{\"name\":\"x\",\"type\":\"minecraft:cow\",\"copy_policy\":\"SAFE_STATE\",\"copy_state\":{\"age\":-24000,\"silent\":true}}");assertEquals(safe,NativeEntityDefinition.parse(safe.source()));
        assertThrows(IllegalArgumentException.class,()->NativeEntityDefinition.parse(safe.source().replace("\"age\"","\"uuid\"")));
        assertThrows(IllegalArgumentException.class,()->NativeEntityDefinition.parse(safe.source().replace("\"SAFE_STATE\"","\"MOD_ADAPTER\"")));
        assertThrows(IllegalArgumentException.class,()->NativeEntityDefinition.parse(safe.source().replace("-24000","{\"UUID\":\"hidden\"}")));
    }
    @Test void defaultsRetainNativeAiAndRoundTrip() {
        var d = NativeEntityDefinition.parse("{\"name\":\"新娜迦\",\"type\":\"twilightforest:naga\",\"attributes\":{\"minecraft:max_health\":240}}");
        assertFalse(d.noAi()); assertEquals(d, NativeEntityDefinition.parse(d.source()));
        assertEquals(240, d.attributes().get("minecraft:max_health"));
    }
    @Test void rejectsIdentityLinksOpaqueNbtAndInvalidShapes() {
        for (String extra : new String[]{",\"UUID\":\"abc\"",",\"nbt\":{}",",\"no_ai\":1",",\"attributes\":[]",",\"equipment\":{\"unknown\":{}}",",\"name\":\"duplicate\""})
            assertThrows(IllegalArgumentException.class, () -> NativeEntityDefinition.parse("{\"name\":\"x\",\"type\":\"minecraft:cow\""+extra+"}"));
        assertThrows(IllegalArgumentException.class, () -> NativeEntityDefinition.parse("{\"name\":\"x\",\"type\":\"minecraft:player\"}"));
    }
    @Test void equipmentIsExplicitAndPreservesComponents() {
        var d=NativeEntityDefinition.parse("{\"name\":\"骑士\",\"type\":\"minecraft:zombie\",\"equipment\":{\"mainhand\":{\"id\":\"minecraft:iron_sword\",\"count\":1}}}");
        assertTrue(d.equipment().get("mainhand").contains("iron_sword"));
    }
}
