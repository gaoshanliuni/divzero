package dev.mineagent.runtime.core.interaction;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Validated native event policy shared by direct rules and reusable ability bindings. */
public record InteractionRuleSpec(String name,String dimension,List<Integer> position,String entityId,Set<String> events,String decision,String keyItem,String keyInstance,String keyPart,Set<UUID> allow,List<Map<String,String>> deniedActions,List<Map<String,String>> allowedActions,boolean enabled,int cooldownTicks,boolean requireKey,String damageType,String source) {
 public static final Set<String> EVENTS=Set.of("block_use","block_break","block_place","entity_use","entity_attack","item_use","item_use_start","item_use_finish","living_jump","incoming_damage","living_death");
 private static final Set<String> OBSERVATIONS=Set.of("item_use_finish","living_jump","living_death");
 private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 public static InteractionRuleSpec parse(String source){return parse(source,false);}
 /** Templates deliberately have no world target. Binding validates the materialized rule again. */
 public static InteractionRuleSpec template(String source){return parse(source,true);}
 public static String bind(String template,String dimension,List<Integer> position,String entityId){try{ObjectNode n=(ObjectNode)JSON.readTree(template);n.put("dimension",dimension);if(position!=null&&!position.isEmpty())n.set("position",JSON.valueToTree(position));else n.put("entity_id",entityId);return parse(n.toString()).source();}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("INTERACTION_RULE_INVALID",e);}}
 private static InteractionRuleSpec parse(String source,boolean template){try{
  if(source==null||source.length()>12000)throw bad("source","expected JSON up to 12000 characters");var j=JSON.readTree(source);
  keys(j,"source","name","dimension","position","entity_id","events","decision","key_item","key_instance","key_part","allow_players","on_denied","on_allowed","enabled","cooldown_ticks","require_key","damage_type");
  String name=text(j,"name","交互规则",80),dim=text(j,"dimension","minecraft:overworld",128);id(dim,"dimension");
  var position=new ArrayList<Integer>();if(j.has("position")){if(!j.get("position").isArray()||j.get("position").size()!=3)throw bad("position","expected [x,y,z]");int i=0;for(var n:j.get("position")){if(!n.isIntegralNumber()||!n.canConvertToInt()||Math.abs((long)n.intValue())>30000000)throw bad("position["+i+"]","expected integer world coordinate");position.add(n.intValue());i++;}}
  String entity=text(j,"entity_id","",36);if(!entity.isEmpty())uuid(entity,"entity_id");
  if(template){if(j.has("position")||j.has("entity_id")||j.has("dimension"))throw bad("source","ability template must not contain a target or dimension; use bind_event_ability");}else if(position.isEmpty()==entity.isEmpty())throw bad("source","exactly one position or entity_id required");
  var events=new LinkedHashSet<String>();if(!j.path("events").isArray())throw bad("events","expected event name array");int ei=0;for(var n:j.get("events")){String e=n.asText();if(!n.isTextual()||!EVENTS.contains(e))throw bad("events["+ei+"]","unsupported event: "+e);if(!template&&position.isEmpty()==e.startsWith("block_"))throw bad("events["+ei+"]","block events require position; other events require entity_id");events.add(e);ei++;}if(events.isEmpty())throw bad("events","at least one event required");if(events.stream().anyMatch(e->e.startsWith("block_"))&&events.stream().anyMatch(e->!e.startsWith("block_")))throw bad("events","one ability cannot mix block and entity targets");
  String decision=text(j,"decision","cancel",16);if(!Set.of("cancel","continue").contains(decision))throw bad("decision","expected cancel or continue");if(decision.equals("cancel")&&events.stream().anyMatch(OBSERVATIONS::contains))throw bad("decision","jump, finish and death are observation hooks; use continue");
  String item=text(j,"key_item","",128),instance=text(j,"key_instance","",36),part=text(j,"key_part","",64);if(!item.isEmpty())id(item,"key_item");if(!instance.isEmpty())uuid(instance,"key_instance");if(instance.isEmpty()!=part.isEmpty())throw bad("key_part","requires key_instance and key_part together");
  var allow=new HashSet<UUID>();if(j.has("allow_players")){if(!j.get("allow_players").isArray()||j.get("allow_players").size()>128)throw bad("allow_players","expected at most 128 UUIDs");int i=0;for(var n:j.get("allow_players"))allow.add(uuid(n.asText(),"allow_players["+(i++)+"]"));}
  boolean enabled=bool(j,"enabled",true),requireKey=bool(j,"require_key",false);if(requireKey&&item.isEmpty()&&instance.isEmpty())throw bad("require_key","key_item or key_instance required");
  String damageType=text(j,"damage_type","",128);if(!damageType.isEmpty()){id(damageType,"damage_type");if(!events.equals(Set.of("incoming_damage")))throw bad("damage_type","only for incoming_damage");}
  var denied=actions(j.path("on_denied"),"on_denied",events);var allowed=actions(j.path("on_allowed"),"on_allowed",events);
  return new InteractionRuleSpec(name,dim,List.copyOf(position),entity,Set.copyOf(events),decision,item,instance,part,Set.copyOf(allow),denied,allowed,enabled,integer(j,"cooldown_ticks",0,0,72000),requireKey,damageType,JSON.writeValueAsString(j));
 }catch(IllegalArgumentException e){throw e;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw bad("source","JSON line "+e.getLocation().getLineNr()+", column "+e.getLocation().getColumnNr()+": "+e.getOriginalMessage());}catch(Exception e){throw new IllegalArgumentException("INTERACTION_RULE_INVALID",e);}}
 private static List<Map<String,String>> actions(JsonNode n,String path,Set<String> events){
  if(n.isMissingNode())return List.of();if(!n.isArray()||n.size()>8)throw bad(path,"expected at most 8 actions");var out=new ArrayList<Map<String,String>>();int index=0;
  for(var a:n){String at=path+"["+(index++)+"]",type=text(a,"type","",24);var row=new LinkedHashMap<String,String>();row.put("type",type);
   switch(type){
    case "message","sound","command"->{keys(a,at,"type","value");String value=text(a,"value","",512);if(value.isBlank())throw bad(at+".value","nonempty string required");if(type.equals("sound"))id(value,at+".value");row.put("value",value);}
    case "effect"->{keys(a,at,"type","id","level","duration_ticks","target");String id=text(a,"id","",128);id(id,at+".id");row.put("id",id);row.put("level",Integer.toString(integer(a,"level",1,1,256)));row.put("duration_ticks",Integer.toString(integer(a,"duration_ticks",100,1,1728000)));row.put("target",target(a,at));}
    case "velocity"->{keys(a,at,"type","x","y","z","target");for(String k:List.of("x","y","z"))row.put(k,Double.toString(number(a,k,0,-4,4,at)));row.put("target",target(a,at));}
    case "damage_scale"->{keys(a,at,"type","value");if(!events.equals(Set.of("incoming_damage")))throw bad(at,"damage_scale requires only incoming_damage");row.put("value",Double.toString(number(a,"value",1,0,16,at)));}
    case "use_duration"->{keys(a,at,"type","value");if(!events.equals(Set.of("item_use_start")))throw bad(at,"use_duration requires only item_use_start");row.put("value",Integer.toString(integer(a,"value",32,1,72000)));}
    default->throw bad(at+".type","unsupported native action: "+type);
   }out.add(Map.copyOf(row));
  }return List.copyOf(out);
 }
 private static String target(JsonNode a,String path){String t=text(a,"target","subject",16);if(!Set.of("subject","actor").contains(t))throw bad(path+".target","expected subject or actor");return t;}
 private static void keys(JsonNode n,String path,String... keys){if(n==null||!n.isObject())throw bad(path,"expected object");for(var e:n.properties())if(!Set.of(keys).contains(e.getKey()))throw bad(path+"."+e.getKey(),"unknown field");}
 private static String text(JsonNode n,String k,String fallback,int max){if(!n.has(k))return fallback;var v=n.get(k);if(!v.isTextual()||v.asText().length()>max)throw bad(k,"expected string, maximum "+max);return v.asText();}
 private static boolean bool(JsonNode n,String k,boolean fallback){if(!n.has(k))return fallback;if(!n.get(k).isBoolean())throw bad(k,"expected boolean");return n.get(k).booleanValue();}
 private static int integer(JsonNode n,String k,int fallback,int min,int max){if(!n.has(k))return fallback;if(!n.get(k).isIntegralNumber()||!n.get(k).canConvertToInt())throw bad(k,"expected integer");return (int)number(n,k,fallback,min,max,"");}
 private static double number(JsonNode n,String k,double fallback,double min,double max,String path){if(!n.has(k))return fallback;var v=n.get(k);if(!v.isNumber()||!Double.isFinite(v.doubleValue())||v.doubleValue()<min||v.doubleValue()>max)throw bad(path+"."+k,"expected number "+min+".."+max);return v.doubleValue();}
 private static void id(String v,String path){if(!v.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))throw bad(path,"expected namespaced registry ID");}
 private static UUID uuid(String v,String path){try{return UUID.fromString(v);}catch(Exception e){throw bad(path,"expected UUID");}}
 private static IllegalArgumentException bad(String path,String reason){return new IllegalArgumentException("INTERACTION_RULE_INVALID at "+path+": "+reason+"; execution NOT_STARTED");}
}
