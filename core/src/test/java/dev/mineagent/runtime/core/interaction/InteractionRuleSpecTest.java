package dev.mineagent.runtime.core.interaction;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class InteractionRuleSpecTest {
 @Test void placementUseAndBreakAreIndependent(){for(String e:java.util.List.of("block_place","block_use","block_break")){var spec=InteractionRuleSpec.parse("{\"position\":[1,64,2],\"events\":[\""+e+"\"]}");assertEquals(java.util.Set.of(e),spec.events());}}

 @Test void doorKeyIsRealCallbackPolicyNotCosmetic(){var rule=InteractionRuleSpec.parse("{\"position\":[1,64,2],\"events\":[\"block_use\",\"block_break\"],\"key_item\":\"minecraft:stick\",\"on_denied\":[{\"type\":\"message\",\"value\":\"locked\"}]}");assertEquals("cancel",rule.decision());assertEquals("minecraft:stick",rule.keyItem());assertEquals(2,rule.events().size());assertEquals("locked",rule.deniedActions().getFirst().get("value"));}
 @Test void ambiguousOrMismatchedTargetsAreRejected(){assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.parse("{\"position\":[1,2,3],\"entity_id\":\"00000000-0000-0000-0000-000000000001\",\"events\":[\"block_use\"]}"));assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.parse("{\"position\":[1,2,3],\"events\":[\"entity_use\"]}"));}

 @Test void reusableTemplateBindsAndEnforcesNativeEventContracts(){
  var source="{\"name\":\"跳跃加速\",\"events\":[\"living_jump\"],\"decision\":\"continue\",\"cooldown_ticks\":20,\"on_allowed\":[{\"type\":\"effect\",\"id\":\"minecraft:speed\",\"level\":2,\"duration_ticks\":40}]}";
  assertEquals(20,InteractionRuleSpec.template(source).cooldownTicks());var bound=InteractionRuleSpec.parse(InteractionRuleSpec.bind(source,"minecraft:overworld",java.util.List.of(),"00000000-0000-0000-0000-000000000001"));assertEquals(1,bound.allowedActions().size());assertFalse(bound.entityId().isEmpty());
  assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.template(source.replace("continue","cancel")));
  assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.bind(source,"minecraft:overworld",java.util.List.of(0,64,0),""));
 }
 @Test void typedEventActionsRejectWrongFieldsWithoutExecuting(){
  var error=assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.template("{\"events\":[\"living_jump\"],\"decision\":\"continue\",\"on_allowed\":[{\"type\":\"damage_scale\",\"value\":0.5}]}"));assertTrue(error.getMessage().contains("on_allowed[0]"));assertTrue(error.getMessage().contains("NOT_STARTED"));
  assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.template("{\"events\":[\"incoming_damage\"],\"enabled\":\"false\"}"));
  assertThrows(IllegalArgumentException.class,()->InteractionRuleSpec.template("{\"events\":[\"incoming_damage\"],\"events\":[\"living_jump\"]}"));
  assertTrue(InteractionRuleSpec.template("{\"events\":[\"incoming_damage\"],\"decision\":\"continue\",\"on_allowed\":[{\"type\":\"damage_scale\",\"value\":0.5}]} ").enabled());
 }
 @Test void narrowGrantsFollowTargetsAndComposeWithActions(){
  var block=InteractionRuleSpec.template("{\"events\":[\"block_use\"]}");
  var entity=InteractionRuleSpec.template("{\"events\":[\"entity_use\"]}");
  var mixed=InteractionRuleSpec.template("{\"events\":[\"block_use\"],\"on_allowed\":[{\"type\":\"velocity\",\"y\":0.2,\"target\":\"actor\"}]}");
  var world=dev.mineagent.runtime.api.permission.PermissionAction.EDIT_WORLD;var entities=dev.mineagent.runtime.api.permission.PermissionAction.EDIT_ENTITIES;
  assertEquals(java.util.Set.of(world),block.permissions());assertEquals(java.util.Set.of(entities),entity.permissions());assertEquals(java.util.Set.of(world,entities),mixed.permissions());
  var permissions=new dev.mineagent.runtime.core.permission.PermissionService();var player=java.util.UUID.randomUUID();permissions.setTrustedActions(player,java.util.Set.of(entities));
  assertTrue(entity.permissions().stream().allMatch(a->permissions.allowed(player,false,a)));assertFalse(mixed.permissions().stream().allMatch(a->permissions.allowed(player,false,a)));
  permissions.setTrustedActions(player,java.util.Set.of());assertFalse(entity.permissions().stream().allMatch(a->permissions.allowed(player,false,a)));
 }
}
