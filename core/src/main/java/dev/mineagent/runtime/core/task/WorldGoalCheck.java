package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.*;
import java.util.*;
public record WorldGoalCheck(String kind,int x,int y,int z,String id,int count,Map<String,String> details) {
    public WorldGoalCheck(String kind,int x,int y,int z,String id,int count){this(kind,x,y,z,id,count,Map.of());}
    public WorldGoalCheck{details=details==null?Map.of():Map.copyOf(details);}
    public boolean matchesAppearance(Map<String,?> state){
        if(!kind.equals("appearance")||!details.keySet().equals(Set.of("model","texture","animation","revision"))||!Boolean.TRUE.equals(state.get("runtimeAvailable"))||!details.get("revision").equals(String.valueOf(state.get("revision")))||!(state.get("nativeSelection") instanceof Map<?,?> nativeState))return false;
        return List.of("model","texture","animation").stream().allMatch(key->details.get(key).equals(state.get(key))&&details.get(key).equals(nativeState.get(key)));
    }
    public static List<WorldGoalCheck> parse(String source){
        try{
            var node=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).readTree(source);
            if(source.length()>65536||!node.isObject()||node.size()!=1||!node.path("checks").isArray()||node.path("checks").isEmpty()||node.path("checks").size()>256)throw new IllegalArgumentException();
            var result=new ArrayList<WorldGoalCheck>();var deliveryIds=new HashSet<UUID>();
            for(var check:node.path("checks")){
                if(check.path("kind").asText().equals("content_delivery")){var goal=dev.mineagent.runtime.core.delivery.DeliveryGoal.parse(check);if(!deliveryIds.add(goal.deliveryId()))throw new IllegalArgumentException("DUPLICATE_DELIVERY_GOAL");result.add(new WorldGoalCheck("content_delivery",0,0,0,goal.deliveryId().toString(),0,Map.of("request",new ObjectMapper().writeValueAsString(goal.wire()))));continue;}
                if(check.path("kind").asText().equals("world_rule")){result.add(WorldRuleGoal.parse(check));continue;}
                if(check.path("kind").asText().equals("shared_state")){
                    var fields=new HashSet<>(dev.mineagent.runtime.core.shared.SharedStateTarget.FIELDS);fields.addAll(Set.of("kind","key","expected_value","schema_version"));if(check.has("exists")){fields.add("exists");if(!check.get("exists").isBoolean()||!check.get("exists").booleanValue()&&!check.get("expected_value").isNull())throw new IllegalArgumentException();}var actualKeys=new HashSet<String>();check.fieldNames().forEachRemaining(actualKeys::add);if(!actualKeys.equals(fields)||!check.path("key").isTextual()||!check.path("schema_version").isIntegralNumber()||!check.path("schema_version").canConvertToInt()||check.path("schema_version").asInt()<1)throw new IllegalArgumentException();
                    var target=dev.mineagent.runtime.core.shared.SharedStateTarget.parse(check,true);var request=new LinkedHashMap<>(target.wire());request.put("keys",List.of(check.path("key").asText()));var encoded=dev.mineagent.runtime.core.shared.SharedStateToolRequest.parse("read_shared_state",new ObjectMapper().writeValueAsString(request)).canonical();String expected=check.get("expected_value").toString();if(expected.length()>8192)throw new IllegalArgumentException();
                    result.add(new WorldGoalCheck("shared_state",0,0,0,target.instanceId().toString(),0,Map.of("request",encoded,"key",check.path("key").asText(),"expected_value",expected,"schema_version",check.path("schema_version").asText(),"exists",check.path("exists").isMissingNode()?"true":check.path("exists").asText())));continue;
                }
                if(check.path("kind").asText().equals("schedule_definition")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);var r=check.path("revision");if(!keys.equals(Set.of("kind","schedule_id","revision","state"))||!check.path("schedule_id").isTextual()||!r.isIntegralNumber()||!r.canConvertToLong()||r.longValue()<1||!Set.of("ACTIVE","PAUSED","CANCELLED","FINISHED","EXPIRED").contains(check.path("state").asText()))throw new IllegalArgumentException();
                    result.add(new WorldGoalCheck("schedule_definition",0,0,0,UUID.fromString(check.path("schedule_id").asText()).toString(),0,Map.of("revision",r.asText(),"state",check.path("state").asText())));continue;
                }
                if(check.path("kind").asText().equals("schedule_occurrence")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);var index=check.path("index");if(!keys.equals(Set.of("kind","schedule_id","index","state"))||!check.path("schedule_id").isTextual()||!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||index.intValue()>31||!Set.of("RECORDED","QUEUED","CLAIMED","DISPATCHED","COMPLETED","CANCELLED","INTERRUPTED","MODEL_BUDGET_EXHAUSTED","FAILED","SCRIPT_QUEUED","SCRIPT_DISPATCHING","SCRIPT_HANDLED","SCRIPT_CANCELLED","SCRIPT_INTERRUPTED","PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING","PUSH_NO_RECIPIENTS","PUSH_READ_DELIVERED","PUSH_PARTIAL_OR_FAILED","PUSH_INTERRUPTED","DELIVERY_QUEUED","DELIVERY_DISPATCHING","DELIVERY_RECORDED","DELIVERY_NO_RECIPIENTS","DELIVERY_CANCELLED","DELIVERY_INTERRUPTED").contains(check.path("state").asText()))throw new IllegalArgumentException();
                    result.add(new WorldGoalCheck("schedule_occurrence",0,0,0,UUID.fromString(check.path("schedule_id").asText()).toString(),index.intValue(),Map.of("state",check.path("state").asText())));continue;
                }
                if(check.path("kind").asText().equals("event_subscription")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);var r=check.path("revision");if(!keys.equals(Set.of("kind","subscription_id","revision","state"))||!check.path("subscription_id").isTextual()||!r.isIntegralNumber()||!r.canConvertToLong()||r.longValue()<1||!Set.of("ACTIVE","PAUSED","CANCELLED").contains(check.path("state").asText()))throw new IllegalArgumentException();
                    result.add(new WorldGoalCheck("event_subscription",0,0,0,UUID.fromString(check.path("subscription_id").asText()).toString(),0,Map.of("revision",r.asText(),"state",check.path("state").asText())));continue;
                }
                if(check.path("kind").asText().equals("audience_definition")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);var r=check.path("revision");
                    if(!keys.equals(Set.of("kind","audience_id","revision","state"))||!check.path("audience_id").isTextual()||!r.isIntegralNumber()||!r.canConvertToLong()||r.longValue()<1||r.longValue()>9_007_199_254_740_991L||!Set.of("ACTIVE","REVOKED").contains(check.path("state").asText()))throw new IllegalArgumentException();
                    result.add(new WorldGoalCheck("audience_definition",0,0,0,UUID.fromString(check.path("audience_id").asText()).toString(),0,Map.of("revision",r.asText(),"state",check.path("state").asText())));continue;
                }
                if(check.path("kind").asText().equals("audience_snapshot")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);var n=check.path("minimum_eligible");
                    if(!keys.equals(Set.of("kind","snapshot_id","minimum_eligible"))||!check.path("snapshot_id").isTextual()||!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<0||n.intValue()>64)throw new IllegalArgumentException();
                    result.add(new WorldGoalCheck("audience_snapshot",0,0,0,UUID.fromString(check.path("snapshot_id").asText()).toString(),n.intValue()));continue;
                }
                if(check.path("kind").asText().equals("native_api_observation")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);if(!keys.equals(Set.of("kind","operation_id","snapshot","minimum_count"))||!check.path("operation_id").isTextual()||!check.path("snapshot").isTextual()||!check.path("snapshot").asText().matches("(?:[a-f0-9]{64})?")||!check.path("minimum_count").isIntegralNumber()||!check.path("minimum_count").canConvertToInt())throw new IllegalArgumentException();
                    int count=check.path("minimum_count").intValue();if(count<0||count>8192)throw new IllegalArgumentException();result.add(new WorldGoalCheck("native_api_observation",0,0,0,UUID.fromString(check.path("operation_id").textValue()).toString(),count,Map.of("snapshot",check.path("snapshot").textValue())));continue;
                }
                if(check.path("kind").asText().equals("native_knowledge")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);String state=check.path("state").asText(),semantic=check.path("semantic_hash").asText();if(!keys.equals(Set.of("kind","knowledge_id","state","semantic_hash"))||!check.path("knowledge_id").isTextual()||!Set.of("CURRENT","CHANGED_REVIEW_REQUIRED","MISSING","FORGOTTEN").contains(state)||!semantic.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();result.add(new WorldGoalCheck("native_knowledge",0,0,0,UUID.fromString(check.path("knowledge_id").textValue()).toString(),0,Map.of("state",state,"semantic_hash",semantic)));continue;
                }
                if(check.path("kind").asText().equals("directory_observation")){
                    var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);
                    if(!keys.equals(Set.of("kind","operation_id","minimum_count"))||!check.path("operation_id").isTextual()||!check.path("minimum_count").isIntegralNumber()||!check.path("minimum_count").canConvertToInt())throw new IllegalArgumentException();
                    int count=check.path("minimum_count").intValue();if(count<0||count>32)throw new IllegalArgumentException();result.add(new WorldGoalCheck("directory_observation",0,0,0,UUID.fromString(check.path("operation_id").textValue()).toString(),count));continue;
                }
                if(check.path("kind").asText().equals("appearance")){
                    var copy=((com.fasterxml.jackson.databind.node.ObjectNode)check).deepCopy();copy.remove("kind");
                    result.add(new WorldGoalCheck("appearance",0,0,0,"",0,AppearanceToolArguments.parse(copy,"revision")));continue;
                }
                if(check.path("kind").asText().equals("world_instance")){
                    var allowed=Set.of("kind","instance_id","minimum_blocks","minimum_objects");check.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException();});
                    if(!check.path("instance_id").isTextual())throw new IllegalArgumentException();var footprint=dev.mineagent.runtime.core.objects.WorldFootprint.parse(check);
                    result.add(new WorldGoalCheck("world_instance",0,0,0,UUID.fromString(check.path("instance_id").textValue()).toString(),footprint.blocks(),check.has("minimum_objects")?Map.of("minimum_objects",Integer.toString(footprint.objects())):Map.of()));continue;
                }
                String kind=check.path("kind").asText();var keys=new HashSet<String>();check.fieldNames().forEachRemaining(keys::add);
                var wanted=switch(kind){case "world_instance"->Set.of("kind","instance_id","minimum_blocks");case "block"->Set.of("kind","x","y","z","block");case "position"->Set.of("kind","x","y","z");case "inventory"->Set.of("kind","item","count");case "food","health"->Set.of("kind","minimum");default->throw new IllegalArgumentException();};if(!keys.equals(wanted))throw new IllegalArgumentException();
                int x=0,y=0,z=0,count=0;String id="";
                if(kind.equals("world_instance")){if(!check.path("instance_id").isTextual()||!check.path("minimum_blocks").isIntegralNumber()||!check.path("minimum_blocks").canConvertToInt())throw new IllegalArgumentException();id=UUID.fromString(check.path("instance_id").textValue()).toString();count=check.path("minimum_blocks").intValue();if(count<1||count>128)throw new IllegalArgumentException();}
                if(Set.of("position","block").contains(kind)){for(var field:List.of("x","y","z"))if(!check.path(field).isIntegralNumber()||!check.path(field).canConvertToInt())throw new IllegalArgumentException();x=check.path("x").intValue();y=check.path("y").intValue();z=check.path("z").intValue();if(Math.abs((long)x)>30000000||Math.abs((long)z)>30000000||y< -2048||y>2048)throw new IllegalArgumentException();}
                if(kind.equals("block"))id=check.path("block").asText();if(kind.equals("inventory")){id=check.path("item").asText();if(!check.path("count").isIntegralNumber()||!check.path("count").canConvertToInt())throw new IllegalArgumentException();count=check.path("count").intValue();if(count<0||count>1000000)throw new IllegalArgumentException();}
                if(Set.of("food","health").contains(kind)){if(!check.path("minimum").isIntegralNumber()||!check.path("minimum").canConvertToInt())throw new IllegalArgumentException();count=check.path("minimum").intValue();if(count<0||count>(kind.equals("food")?20:2048))throw new IllegalArgumentException();}
                if(Set.of("inventory","block").contains(kind)&&!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException();result.add(new WorldGoalCheck(kind,x,y,z,id,count));
            }WorldRuleGoal.requireDataChecks(result);return List.copyOf(result);
        }catch(Exception e){throw new IllegalArgumentException("WORLD_GOAL_CHECKS_INVALID",e);}
    }
}
