package dev.mineagent.runtime.core.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Bounded current controls; world objects and raw combat histories never enter the panel serializer. */
public final class BehaviorPanelProjection {
    public static Map<String,Object> snapshot(Map<String,Object> source){
        var mapper=new ObjectMapper();var byActor=new LinkedHashMap<String,Object>();
        if(source.get("skills") instanceof Iterable<?> rows)for(var item:rows)if(item instanceof Map<?,?> row){
            var session=mapper.valueToTree(row.get("session"));if(Set.of("COMPLETED","FAILED","CANCELLED").contains(session.path("state").asText()))continue;
            var visible=((ObjectNode)session).deepCopy();var counters=mapper.createObjectNode();
            for(String key:List.of("verifiedHits","damageMilliHearts","nativeDamageTakenMilli","harvested","planted","fishingCatches"))counters.put(key,session.path("counters").path(key).asLong());visible.set("counters",counters);
            String target=row.get("combat") instanceof Map<?,?> combat?Objects.toString(combat.get("targetName"),""):"";
            byActor.put(session.path("spec").path("actor").asText(),Map.of("session",visible,"combat",Map.of("targetName",target),"tactic",Objects.toString(row.get("tactic"),"")));
        }
        return Map.of("status","OBSERVED","skills",List.copyOf(byActor.values()));
    }
    public static Map<String,Object> receipt(Map<String,Object> value){
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("status","error","errorCode","executionState","reason","mode","dimension","position","settings","workPreserved"))if(value.containsKey(key))result.put(key,value.get(key));
        result.putIfAbsent("status","APPLIED");return result;
    }
    private BehaviorPanelProjection(){}
}
