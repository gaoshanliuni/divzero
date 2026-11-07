package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.conversation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingSpecTest {
    @Test void recipeLookupDoesNotExpandExactOutputIntoEveryConsumer() {
        var ids=List.of("minecraft:oak_planks","minecraft:chest","minecraft:trapped_chest","other:chest");
        assertEquals(Set.of("minecraft:oak_planks"),RecipeQuery.exactIds(ids,"oak_planks"));
        assertEquals(Set.of("minecraft:chest"),RecipeQuery.exactIds(ids,"minecraft:chest"));
        assertEquals(Set.of("minecraft:chest","other:chest"),RecipeQuery.exactIds(ids,"chest"));
        assertTrue(RecipeQuery.exactIds(ids,"plank").isEmpty());assertTrue(RecipeQuery.exactIds(ids,"").isEmpty());
    }
    private final ObjectMapper json = new ObjectMapper();
    private com.fasterxml.jackson.databind.node.ObjectNode intent() throws Exception {
        return (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("""
                {"id":"make_armor","kind":"PROCESS","dimension":"minecraft:overworld","expected_revision":0,
                 "process":{"action":"CRAFT","recipe":"minecraft:diamond_chestplate","recipe_reference":"observed-7","count":1,"station":[4,64,8]}}
                """);
    }
    @Test void retainedThroughCombatAndPersistence() throws Exception {
        var spec = SkillSpec.parse(intent(), null);
        assertEquals(spec.process(), spec.withCombat(CombatPolicy.defaults(SkillSpec.Kind.IDLE, false, "")).process());
        assertEquals(spec, json.readValue(json.writeValueAsString(spec), SkillSpec.class));
        assertEquals(spec.process(), spec.adjust(json.readTree("{\"dwell_ticks\":30}")).process());
    }
    @Test void diagnosticsPointToBadFieldBeforeExecution() throws Exception {
        var n = intent(); ((com.fasterxml.jackson.databind.node.ObjectNode)n.get("process")).put("count", 1.5);
        var badCount = n; assertTrue(assertThrows(IllegalArgumentException.class, () -> SkillSpec.parse(badCount, null)).getMessage().contains("process.count"));
        n = intent(); ((com.fasterxml.jackson.databind.node.ObjectNode)n.get("process")).putArray("station").add(1.5).add(64).add(3);
        var fractional = n; assertTrue(assertThrows(IllegalArgumentException.class, () -> SkillSpec.parse(fractional, null)).getMessage().contains("process.station"));
        n = intent(); ((com.fasterxml.jackson.databind.node.ObjectNode)n.get("process")).remove("recipe_reference");
        var unobserved = n; assertThrows(IllegalArgumentException.class, () -> SkillSpec.parse(unobserved, null));
    }
    @Test void processingIsFiniteCooperativeStepAndScopedTool() throws Exception {
        UUID agent = UUID.randomUUID(); var n = new CooperationPlan.Node("armor", agent, intent(), List.of());
        assertEquals(SkillSpec.Kind.PROCESS, new CooperationPlan("Armor", List.of(n), List.of()).node("armor").spec().kind());
        assertTrue(AgentToolScope.supports("process_items")); assertTrue(AgentToolScope.supports("inspect_recipes"));
        assertFalse(ConversationTools.mutation("inspect_recipes"));
        var advertised = CapabilityCatalog.selected(List.of("process_items"));
        var schema = json.readTree(advertised.stream().filter(t -> t.name().equals("process_items")).findFirst().orElseThrow().parameters());
        assertTrue(schema.path("properties").has("process")); assertFalse(schema.path("properties").has("crop"));
    }
    @Test void storingCannotInjectRecipeOrOutputAndActorIsExplicit() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ProcessingSpec(ProcessingSpec.Action.STORE,"minecraft:stone","v1","minecraft:diamond",1,new SkillSpec.Point(1,2,3),""));
        var n = intent(); n.put("actor","player");
        assertThrows(IllegalArgumentException.class, () -> SkillSpec.parse(n,null));
    }
    @Test void pendingNativeProcessingNeverReplaysAfterRestartOrRepeatedReadOnlyReconciliation() throws Exception {
        var spec=SkillSpec.parse(intent(),null);
        var session=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,spec);
        session.receipt(Map.of("state","PREPARED","processingPhase","WAITING_NATIVE_SMELT"));
        var restored=SkillSession.restore(session.snapshot());
        assertTrue(restored.needsReconciliation());assertFalse(restored.automaticResumeCandidate());
        assertThrows(IllegalStateException.class,()->restored.control(restored.revision(),"resume"));
        restored.reconcile(restored.revision(),Map.of("observedInventory","actual inventory"));
        restored.reconcile(restored.revision(),Map.of("observedInventory","actual inventory again"));
        assertThrows(IllegalStateException.class,()->restored.control(restored.revision(),"resume"));
        restored.control(restored.revision(),"stop");assertEquals(SkillSession.State.CANCELLED,restored.state());
    }
}
