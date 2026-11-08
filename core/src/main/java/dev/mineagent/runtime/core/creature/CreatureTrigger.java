package dev.mineagent.runtime.core.creature;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Composable native event predicates and state transitions; no executable source. */
public record CreatureTrigger(String id,String event,String item,String projectile,String state,String nextState,
                              boolean ownerOnly,boolean consumeInteraction,int consumeCount,int cooldown,
                              double healthBelow,String target,List<CreatureDefinition.ProximityAction> actions) {
    public static List<CreatureTrigger> parse(JsonNode array){
        if(array==null||array.isMissingNode())return List.of();if(!array.isArray())throw new IllegalArgumentException("CREATURE_TRIGGERS_ARRAY");
        var result=new ArrayList<CreatureTrigger>();var ids=new HashSet<String>();
        for(var n:array){
            for(var field:n.properties())if(!Set.of("id","event","item","projectile","state","next_state","owner_only","consume_interaction","consume_count","cooldown","health_below","target","actions").contains(field.getKey()))throw new IllegalArgumentException("CREATURE_TRIGGER_FIELD:"+field.getKey());
            String id=text(n,"id",""),event=text(n,"event",""),item=text(n,"item",""),projectile=text(n,"projectile",""),state=text(n,"state",""),next=text(n,"next_state",""),target=text(n,"target","actor");
            if(!id.matches("[A-Za-z0-9_.-]{1,64}")||!ids.add(id)||!Set.of("interact","projectile_hit","hurt","health_below").contains(event)||!Set.of("actor","self").contains(target))throw new IllegalArgumentException("CREATURE_TRIGGER_ID_OR_EVENT");
            for(String type:List.of(item,projectile))if(!type.isEmpty()&&!type.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))throw new IllegalArgumentException("CREATURE_TRIGGER_REGISTRY_ID");
            if(n.has("health_below")&&(!n.get("health_below").isNumber()||!Double.isFinite(n.get("health_below").asDouble())))throw new IllegalArgumentException("CREATURE_TRIGGER_HEALTH");
            double health=n.path("health_below").asDouble(1);if(health<=0||health>1)throw new IllegalArgumentException("CREATURE_TRIGGER_HEALTH");
            int consume=integer(n,"consume_count",0),cooldown=integer(n,"cooldown",20);if(consume>64||consume>0&&(!event.equals("interact")||item.isEmpty()))throw new IllegalArgumentException("CREATURE_TRIGGER_CONSUME");
            var actions=new ArrayList<CreatureDefinition.ProximityAction>();if(n.has("actions")&&!n.get("actions").isArray())throw new IllegalArgumentException("CREATURE_TRIGGER_ACTIONS");
            for(var action:n.path("actions")){var parsed=CreatureDefinition.action(action);if(parsed.type().equals("explosion"))throw new IllegalArgumentException("CREATURE_TRIGGER_USE_PROXIMITY_FUSE");actions.add(parsed);}
            if(actions.isEmpty()&&next.isEmpty())throw new IllegalArgumentException("CREATURE_TRIGGER_EMPTY");
            result.add(new CreatureTrigger(id,event,item,projectile,state,next,bool(n,"owner_only"),bool(n,"consume_interaction"),consume,cooldown,health,target,List.copyOf(actions)));
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode n,String field,String fallback){if(!n.has(field))return fallback;if(!n.get(field).isTextual()||n.get(field).asText().length()>128)throw new IllegalArgumentException("CREATURE_TRIGGER_FIELD:"+field);return n.get(field).asText();}
    private static boolean bool(JsonNode n,String key){if(n.has(key)&&!n.get(key).isBoolean())throw new IllegalArgumentException("CREATURE_TRIGGER_FIELD:"+key);return n.path(key).asBoolean(false);}
    private static int integer(JsonNode n,String key,int fallback){if(!n.has(key))return fallback;if(!n.get(key).isIntegralNumber()||!n.get(key).canConvertToInt()||n.get(key).asInt()<0)throw new IllegalArgumentException("CREATURE_TRIGGER_FIELD:"+key);return n.get(key).asInt();}
}
