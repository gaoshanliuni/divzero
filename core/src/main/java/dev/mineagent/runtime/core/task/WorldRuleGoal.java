package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.shared.SharedStateTarget;
import dev.mineagent.runtime.core.shared.SharedStateToolRequest;
import java.util.*;

/** Rule liveness is not application success. Pair it with a pinned shared-state check, verified later by Native. */
public final class WorldRuleGoal {
    private WorldRuleGoal() {}
    public static WorldGoalCheck parse(JsonNode node) throws Exception {
        var fields=new HashSet<>(SharedStateTarget.FIELDS);fields.addAll(Set.of("kind","definition_id"));
        var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);
        if(!fields.equals(actual)||!node.path("definition_id").isTextual())throw new IllegalArgumentException("WORLD_RULE_FIELDS");
        var definition=UUID.fromString(node.path("definition_id").asText());var target=SharedStateTarget.parse(node,true);
        return new WorldGoalCheck("world_rule",0,0,0,target.instanceId().toString(),0,
                Map.of("definition_id",definition.toString(),"target",new ObjectMapper().writeValueAsString(target.wire())));
    }
    public static SharedStateTarget target(WorldGoalCheck check) throws Exception {
        if(!check.kind().equals("world_rule"))throw new IllegalArgumentException("WORLD_RULE_KIND");
        return SharedStateTarget.parse(new ObjectMapper().readTree(check.details().get("target")),true);
    }
    public static void requireDataChecks(List<WorldGoalCheck> checks) throws Exception {
        for(var rule:checks)if(rule.kind().equals("world_rule")){
            var target=target(rule);boolean found=false;
            for(var data:checks)if(data.kind().equals("shared_state")&&target.equals(
                    SharedStateToolRequest.parse("read_shared_state",data.details().get("request")).target())){found=true;break;}
            if(!found)throw new IllegalArgumentException("WORLD_RULE_DATA_PROOF_REQUIRED");
        }
    }
}
