package dev.mineagent.runtime.neoforge;

import dev.mineagent.runtime.core.creature.CreatureDefinition;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAcceptanceFixtureTest {
    @Test void bundledRigParsesWithoutRelaxingCollisionRules()throws Exception {
        try(var input=getClass().getResourceAsStream("/mineagent/smoke/rig-fixture.json")){
            assertNotNull(input);
            var definition=CreatureDefinition.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(5,definition.bones().size());
            assertEquals("forearm",definition.bones().getLast().parent());
            assertEquals(java.util.Set.of("arm","head"),definition.animations().get("idle").bones().keySet());
            for(var bone:definition.bones())assertNotNull(bone.mesh());
        }
    }
}
