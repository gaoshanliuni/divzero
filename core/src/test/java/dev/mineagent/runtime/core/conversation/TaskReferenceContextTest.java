package dev.mineagent.runtime.core.conversation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskReferenceContextTest {
    @Test void recipeInventoryAvailabilityKeepsItsActualActorScope(){
        var state=new TaskReferenceContext();
        for(String actor:List.of("builder","courier"))state.observe("inspect_recipes",Map.of("status","OBSERVED","actorId",actor,"recipes",List.of(Map.of("recipe","minecraft:chest","recipe_reference","same-recipe-version"))),UUID.randomUUID(),1);
        var rows=state.snapshot().path("references");assertEquals(2,rows.size());assertEquals("builder",rows.get(0).path("inventoryActor").asText());assertEquals("courier",rows.get(1).path("inventoryActor").asText());
    }
    @Test void identityPairAndRecipeVersionSurviveUnrelatedLargeObservations(){
        var state=new TaskReferenceContext();UUID operation=UUID.randomUUID();
        state.observe("inspect_owned_agents",Map.of("status","OBSERVED","agents",List.of(Map.of("agent_id","wood","name","木材伙伴","revision",1,"body_uuid","body-wood"),Map.of("agent_id","stone","name","矿物伙伴","revision",1,"body_uuid","body-stone"))),operation,100);
        state.observe("inspect_recipes",Map.of("status","OBSERVED","recipes",List.of(Map.of("recipe","minecraft:chest","recipe_reference","version1","definition",Map.of("result",Map.of("id","minecraft:chest","count",1)),"ingredients",List.of(Map.of("slots",8,"alternatives",List.of(Map.of("item","minecraft:oak_planks","available",0))))))),operation,101);
        state.observe("scan_blocks",Map.of("status","OBSERVED","blocks","large unrelated world observation"),UUID.randomUUID(),102);
        var rows=state.snapshot().path("references");assertEquals(3,rows.size());assertEquals("木材伙伴",rows.get(0).path("name").asText());assertEquals("body-wood",rows.get(0).path("body_uuid").asText());assertEquals("stone",rows.get(1).path("agent_id").asText());assertEquals("version1",rows.get(2).path("recipe_reference").asText());assertEquals(8,rows.get(2).path("ingredients").get(0).path("slots").asInt());assertFalse(state.snapshot().path("completionEvidence").asBoolean());
    }
    @Test void newerObservationReplacesReferenceButFailureDoesNotInventOne(){
        var state=new TaskReferenceContext();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        state.observe("inspect_recipes",Map.of("status","OBSERVED","recipes",List.of(Map.of("recipe","mod:armor","recipe_reference","old"))),a,10);
        state.observe("inspect_recipes",Map.of("status","READ_FAILED","recipes",List.of(Map.of("recipe","mod:armor","recipe_reference","invented"))),a,11);
        assertEquals("old",state.snapshot().path("references").get(0).path("recipe_reference").asText());
        state.observe("inspect_recipes",Map.of("status","OBSERVED","recipes",List.of(Map.of("recipe","mod:armor","recipe_reference","new"))),b,12);
        var snapshot=state.snapshot();assertEquals(1,snapshot.path("references").size());assertEquals("new",snapshot.path("references").get(0).path("recipe_reference").asText());assertEquals(b.toString(),snapshot.path("references").get(0).path("record_id").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.path("references").get(0)).put("recipe_reference","tampered");assertEquals("new",state.snapshot().path("references").get(0).path("recipe_reference").asText());
    }
}
