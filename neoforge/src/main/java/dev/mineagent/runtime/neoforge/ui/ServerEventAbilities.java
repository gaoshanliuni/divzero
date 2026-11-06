package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.core.interaction.InteractionRuleSpec;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Persistent, versioned templates materialize into the existing native interaction rule engine. */
public final class ServerEventAbilities {
 private static final ObjectMapper JSON=new ObjectMapper();
 public record Ability(UUID id,UUID owner,long revision,boolean enabled,String source){}
 public record Binding(UUID id,UUID owner,UUID abilityId,long revision,boolean enabled,String dimension,List<Integer> position,String entityId){}
 private static String prefix(MinecraftServer s,String kind){return "interaction."+kind+"."+MineAgentRuntimeServices.worldId(s)+".";}
 private static <T> Map<UUID,T> load(MinecraftServer s,String kind,Class<T> type){var out=new LinkedHashMap<UUID,T>();for(var e:MineAgentRuntimeServices.config(s).snapshot().values().entrySet())if(e.getKey().startsWith(prefix(s,kind))&&!e.getValue().isBlank())try{out.put(UUID.fromString(e.getKey().substring(prefix(s,kind).length())),JSON.readValue(e.getValue(),type));}catch(Exception invalid){throw new IllegalStateException("EVENT_ABILITY_STATE_INVALID",invalid);}return out;}
 public static Optional<Binding> binding(MinecraftServer s,UUID id){return Optional.ofNullable(load(s,"bindings",Binding.class).get(id));}
 public static String bindingKey(MinecraftServer s,UUID id){return prefix(s,"bindings")+id;}
 public static List<Ability> owned(ServerPlayer p){return load(p.level().getServer(),"abilities",Ability.class).values().stream().filter(v->v.owner().equals(p.getUUID())).toList();}
 public static Map<String,Object> inspect(ServerPlayer p,int offset){if(offset<0)throw new IllegalArgumentException("EVENT_ABILITY_OFFSET");var all=owned(p);var bindings=load(p.level().getServer(),"bindings",Binding.class);var page=all.stream().skip(offset).limit(16).map(a->Map.of("ability",a,"bindings",bindings.values().stream().filter(b->b.abilityId().equals(a.id())&&b.owner().equals(p.getUUID())).toList())).toList();return Map.of("abilities",page,"nextOffset",offset+16<all.size()?offset+16:-1,"contract",CONTRACT,"events",InteractionRuleSpec.EVENTS);}
 public static final String CONTRACT="""
  Define a reusable ability with a target-free flat source JSON, then bind to a loaded position or entity_id in the current dimension. Existing set_interaction_rule still supports direct one-target rules (including door keys).
  source: name,events:string[],decision:cancel|continue,enabled:true,cooldown_ticks:0,key_item,key_instance+key_part,require_key:false,allow_players:UUID[],damage_type,on_allowed:[],on_denied:[]. No dimension/position/entity_id in a template.
  block_use/block_break/block_place require a position; entity_use/entity_attack target the interacted entity. item_use/item_use_start/item_use_finish/living_jump/incoming_damage/living_death target the acting/affected entity. item_use means right-click item in air, not every block/item pathway. require_key gates the ability to the actual event item (or held item for non-item events); keys otherwise bypass cancellation as in door locks. damage_type filters incoming_damage by registry ID.
  Actions: {type:message|sound|command,value:string}; {type:effect,id:registry_id,level:1,duration_ticks:100,target:subject|actor}; {type:velocity,x:0,y:0.2,z:0,target:subject|actor} adds native velocity; {type:damage_scale,value:0.5} only for incoming_damage; {type:use_duration,value:16} only for item_use_start. subject is the bound entity, actor is the interacting player (self for jump/use/damage). Message/sound default to actor or subject. Commands run only when actor is the owner using that player's current authority; cannot borrow another player's permissions.
  jump, finish and death are observation hooks and require continue, not fake cancellation. Duration changes actual native use timing; damage_scale changes the real incoming amount before armor, not final health. No action overrides another mod's cancellation. Cooldown limits callbacks/actions, never unlocks a cancel rule. Bindings and templates have separate revisions. Updating a template atomically updates all its bindings; failed validation keeps the old version. control_event_ability enables/disables/removes a template or a binding. Removing a template removes all its bindings. Effects already applied have their ordinary lifetime; disabling prevents future triggers.
  Game management permission is required; no network downloads, Java compilation, Python or per-event model calls. Unknown event/API must use an existing script/Java extension lifecycle instead of claiming arbitrary vanilla code was rewritten.
  """;
 public static Map<String,Object> define(ServerPlayer p,UUID operation,JsonNode a)throws Exception{
  permission(p);var s=p.level().getServer();var abilities=load(s,"abilities",Ability.class);UUID id=a.has("ability_id")?UUID.fromString(a.path("ability_id").asText()):operation;var old=abilities.get(id);long expected=revision(a);check(p,old==null?null:old.owner(),old==null?0:old.revision(),expected);
  if(old==null&&abilities.size()>=512)throw new IllegalStateException("EVENT_ABILITY_CAPACITY");var spec=InteractionRuleSpec.template(a.path("source").asText());ServerInteractionRules.validateRegistry(spec);
  var next=new Ability(id,p.getUUID(),expected+1,old==null||old.enabled(),spec.source());var edits=new LinkedHashMap<String,String>();edits.put(prefix(s,"abilities")+id,JSON.writeValueAsString(next));
  for(var b:load(s,"bindings",Binding.class).values())if(b.abilityId().equals(id)){var updated=new Binding(b.id(),b.owner(),id,b.revision()+1,b.enabled(),b.dimension(),b.position(),b.entityId());materialize(s,next,updated,edits);}
  apply(s,edits);return Map.of("status","APPLIED","abilityId",id,"revision",next.revision(),"nativeCallback",true);
 }
 public static Map<String,Object> bind(ServerPlayer p,UUID operation,JsonNode a)throws Exception{
  permission(p);var s=p.level().getServer();var ability=load(s,"abilities",Ability.class).get(UUID.fromString(a.path("ability_id").asText()));if(ability==null||!ability.owner().equals(p.getUUID()))throw new SecurityException("EVENT_ABILITY_NOT_OWNED");if(ability.revision()!=a.path("ability_revision").asLong(-1))throw new IllegalStateException("EVENT_ABILITY_STALE");
  var bindings=load(s,"bindings",Binding.class);UUID id=a.has("binding_id")?UUID.fromString(a.path("binding_id").asText()):operation;var old=bindings.get(id);long expected=revision(a);check(p,old==null?null:old.owner(),old==null?0:old.revision(),expected);
  if(old==null&&(bindings.size()>=512||!MineAgentRuntimeServices.config(s).snapshot().values().getOrDefault(prefix(s,"rules")+id,"").isBlank()))throw new IllegalStateException("EVENT_BINDING_ID_OR_CAPACITY");
  ObjectNode target=(ObjectNode)JSON.readTree(ability.source());target.put("dimension",p.level().dimension().identifier().toString());if(a.has("position"))target.set("position",a.get("position"));if(a.has("entity_id"))target.set("entity_id",a.get("entity_id"));var spec=InteractionRuleSpec.parse(target.toString());ServerInteractionRules.validateTarget(p,spec);
  var next=new Binding(id,p.getUUID(),ability.id(),expected+1,true,spec.dimension(),spec.position(),spec.entityId());var edits=new LinkedHashMap<String,String>();materialize(s,ability,next,edits);apply(s,edits);return Map.of("status","APPLIED","bindingId",id,"revision",next.revision(),"abilityRevision",ability.revision(),"nativeCallback",true);
 }
 public static Map<String,Object> control(ServerPlayer p,JsonNode a)throws Exception{
  permission(p);var s=p.level().getServer();boolean binding=a.has("binding_id");if(binding==a.has("ability_id"))throw new IllegalArgumentException("EVENT_ABILITY_ONE_ID");String action=a.path("action").asText();if(!Set.of("enable","disable","remove").contains(action))throw new IllegalArgumentException("EVENT_ABILITY_ACTION");UUID id=UUID.fromString(a.path(binding?"binding_id":"ability_id").asText());long expected=revision(a);var abilities=load(s,"abilities",Ability.class);var bindings=load(s,"bindings",Binding.class);var edits=new LinkedHashMap<String,String>();
  if(binding){var b=bindings.get(id);if(b==null)throw new IllegalArgumentException("EVENT_BINDING_NOT_FOUND");check(p,b.owner(),b.revision(),expected);if(action.equals("remove")){edits.put(bindingKey(s,id),"");edits.put(prefix(s,"rules")+id,"");}else materialize(s,abilities.get(b.abilityId()),new Binding(id,b.owner(),b.abilityId(),expected+1,action.equals("enable"),b.dimension(),b.position(),b.entityId()),edits);}
  else{var old=abilities.get(id);if(old==null)throw new IllegalArgumentException("EVENT_ABILITY_NOT_FOUND");check(p,old.owner(),old.revision(),expected);var next=new Ability(id,old.owner(),expected+1,action.equals("enable"),old.source());edits.put(prefix(s,"abilities")+id,action.equals("remove")?"":JSON.writeValueAsString(next));for(var b:bindings.values())if(b.abilityId().equals(id)){if(action.equals("remove")){edits.put(bindingKey(s,b.id()),"");edits.put(prefix(s,"rules")+b.id(),"");}else materialize(s,next,new Binding(b.id(),b.owner(),id,b.revision()+1,b.enabled(),b.dimension(),b.position(),b.entityId()),edits);}}
  apply(s,edits);return Map.of("status",action.equals("remove")?"REMOVED":"APPLIED","id",id,"revision",expected+1,"action",action);
 }
 private static void materialize(MinecraftServer s,Ability ability,Binding b,Map<String,String> edits)throws Exception{
  var source=(ObjectNode)JSON.readTree(InteractionRuleSpec.bind(ability.source(),b.dimension(),b.position(),b.entityId()));source.put("enabled",ability.enabled()&&b.enabled()&&source.path("enabled").asBoolean(true));var spec=InteractionRuleSpec.parse(source.toString());
  edits.put(bindingKey(s,b.id()),JSON.writeValueAsString(b));edits.put(prefix(s,"rules")+b.id(),JSON.writeValueAsString(new ServerInteractionRules.Saved(b.id(),b.owner(),b.revision(),spec.source())));
 }
 private static long revision(JsonNode a){var n=a.path("expected_revision");if(n.isMissingNode())return 0;if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0||n.longValue()==Long.MAX_VALUE)throw new IllegalArgumentException("EVENT_ABILITY_REVISION");return n.longValue();}
 private static void check(ServerPlayer p,UUID owner,long actual,long expected){if(owner!=null&&!owner.equals(p.getUUID()))throw new SecurityException("EVENT_ABILITY_NOT_OWNED");if(actual!=expected)throw new IllegalStateException("EVENT_ABILITY_STALE");}
 private static void permission(ServerPlayer p){if(!p.level().getServer().isSameThread()||!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("INTERACTION_GAMEMASTER_REQUIRED");}
 private static void apply(MinecraftServer s,Map<String,String> edits){var config=MineAgentRuntimeServices.config(s);var result=config.apply(new ConfigPatch(config.snapshot().revision(),edits),true);if(!result.accepted())throw new IllegalStateException(result.errorCode());}
 private ServerEventAbilities(){}
}
