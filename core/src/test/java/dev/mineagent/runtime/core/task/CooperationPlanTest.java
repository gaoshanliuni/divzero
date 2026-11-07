package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooperationPlanTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ObjectNode plan(){
        var p=JSON.createObjectNode().put("name","Collect and build");var rows=p.putArray("nodes");String supplier=UUID.randomUUID().toString(),builder=UUID.randomUUID().toString();
        var g=rows.addObject().put("id","wood").put("agent",supplier);var intent=g.putObject("intent").put("id","wood").put("kind","GATHER").put("expected_revision",0).put("dimension","minecraft:overworld").put("crop","minecraft:oak_log").put("limit",8);intent.putArray("min").add(0).add(64).add(0);intent.putArray("max").add(8).add(75).add(8);
        var h=rows.addObject().put("id","deliver").put("agent",supplier);h.putArray("after").add("wood");h.putObject("intent").put("id","deliver").put("kind","HANDOFF").put("expected_revision",0).put("dimension","minecraft:overworld").put("target",builder).put("crop","minecraft:oak_log").put("limit",8);
        var b=rows.addObject().put("id","house").put("agent",builder);b.putArray("after").add("deliver");b.putObject("intent").put("id","house").put("kind","CONSTRUCT").put("expected_revision",0).put("dimension","minecraft:overworld").put("building_id","home").put("building_revision",3);
        p.putArray("supplies").addObject().put("gather","wood").put("handoff","deliver").put("construction","house");return p;
    }
    @Test void persistsDependenciesAndDoesNotExposeMutableIntent()throws Exception{
        var parsed=CooperationPlan.parse(plan());var restored=JSON.readValue(JSON.writeValueAsString(parsed),CooperationPlan.class);assertEquals(parsed,restored);assertEquals(2,parsed.agents().size());parsed.node("wood").intent().put("limit",500);assertEquals(8,parsed.node("wood").spec().limit());assertEquals(3,parsed.node("house").spec().buildingRevision());
    }
    @Test void rejectsCyclesUnknownDependenciesAndUnrelatedMaterialSupply(){
        var cyclic=plan();((ObjectNode)cyclic.path("nodes").get(0)).putArray("after").add("house");assertThrows(IllegalArgumentException.class,()->CooperationPlan.parse(cyclic));
        var missing=plan();((ObjectNode)missing.path("nodes").get(1)).putArray("after").add("other");assertThrows(IllegalArgumentException.class,()->CooperationPlan.parse(missing));
        var mismatch=plan();((ObjectNode)mismatch.path("nodes").get(1).path("intent")).put("crop","minecraft:stone");assertThrows(IllegalArgumentException.class,()->CooperationPlan.parse(mismatch));
    }
    @Test void rejectsPlayerTakeoverAndAdvertisesRealConstructionFields()throws Exception{
        var value=plan();((ObjectNode)value.path("nodes").get(0).path("intent")).put("actor","player");assertThrows(IllegalArgumentException.class,()->CooperationPlan.parse(value));
        var input=JSON.createObjectNode().set("plan",plan());assertTrue(dev.mineagent.runtime.core.conversation.ToolValidation.check("start_cooperation",(ObjectNode)input).issues().isEmpty());
        var scope=new dev.mineagent.runtime.core.conversation.CapabilitySession();scope.load("building");var tool=scope.definitions().stream().filter(d->d.name().equals("construct_building")).findFirst().orElseThrow();assertTrue(JSON.readTree(tool.parameters()).path("properties").has("building_revision"));
    }
}
