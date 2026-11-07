package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Small observed identities survive execution-log archival; they never substitute for world verification. */
public final class TaskReferenceContext {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final LinkedHashMap<String,ObjectNode> references=new LinkedHashMap<>();
    private long evicted;
    public void observe(String tool,Map<String,Object> result,UUID operation,long observedAt){
        if(!"OBSERVED".equals(result.get("status")))return;
        JsonNode value=JSON.valueToTree(result);
        if(tool.equals("inspect_owned_agents"))for(var agent:value.path("agents")){
            if(!agent.path("agent_id").isTextual())continue;
            var row=copy(agent,"agent_id","name","revision","body_uuid");
            put("agent/"+agent.path("agent_id").asText(),"agent_identity",row,operation,observedAt);
        }
        if(tool.equals("inspect_recipes"))for(var recipe:value.path("recipes")){
            if(!recipe.path("recipe").isTextual()||!recipe.path("recipe_reference").isTextual())continue;
            var row=copy(recipe,"recipe","recipe_reference","type","station","cookingTicks","special","ingredients");
            if(recipe.path("definition").has("result"))row.set("output",recipe.path("definition").get("result").deepCopy());
            put("recipe/"+recipe.path("recipe").asText(),"recipe_observation",row,operation,observedAt);
        }
        trim();
    }
    private void put(String key,String kind,ObjectNode value,UUID operation,long at){
        value.put("kind",kind).put("record_id",operation.toString()).put("observedAt",at);
        references.remove(key);references.put(key,value);
    }
    private static ObjectNode copy(JsonNode source,String... fields){var out=JSON.createObjectNode();for(String field:fields)if(source.has(field))out.set(field,source.get(field).deepCopy());return out;}
    private void trim(){
        long characters=references.values().stream().mapToLong(v->v.toString().length()).sum();var oldest=references.entrySet().iterator();
        while(oldest.hasNext()&&(references.size()>96||characters>24000)){var item=oldest.next();characters-=item.getValue().toString().length();oldest.remove();evicted++;}
    }
    public boolean isEmpty(){return references.isEmpty();}
    public ObjectNode snapshot(){
        var out=JSON.createObjectNode().put("source","actual_tool_observations").put("completionEvidence",false).put("evicted",evicted);
        out.put("meaning","Names and IDs are paired observations. Recipe references belong to the observed recipe version. Inventory availability is historical, not a current-stock guarantee. Revalidate mutable state before execution; full records remain queryable.");
        var rows=out.putArray("references");references.values().forEach(row->rows.add(row.deepCopy()));return out;
    }
}
