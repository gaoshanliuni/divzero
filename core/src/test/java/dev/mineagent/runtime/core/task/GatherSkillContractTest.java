package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GatherSkillContractTest {
 @Test void handoffIsASeparateCountedNativeWorkIntent()throws Exception{
  var input=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"id\":\"bring_stone\",\"dimension\":\"minecraft:overworld\",\"target\":\"$owner\",\"crop\":\"minecraft:cobblestone\",\"limit\":24}");var spec=SkillSpec.parse(input,SkillSpec.Kind.HANDOFF);assertEquals(SkillSpec.Kind.HANDOFF,SkillTools.kind("handoff_items"));assertEquals(24,spec.limit());assertEquals("$owner",spec.target());assertTrue(dev.mineagent.runtime.core.conversation.AgentToolScope.supports("handoff_items"));
 }
 @Test void requiresExplicitResourceCountAndAreaButPreservesCombatPolicy()throws Exception{
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var input=json.readTree("{\"id\":\"wood\",\"dimension\":\"minecraft:overworld\",\"min\":[0,64,0],\"max\":[12,72,12],\"crop\":\"minecraft:oak_log\",\"limit\":32}");
  var spec=SkillSpec.parse(input,SkillSpec.Kind.GATHER);assertEquals(SkillSpec.Kind.GATHER,spec.kind());assertEquals(32,spec.limit());assertEquals(CombatPolicy.Engagement.SELF_DEFENSE,spec.combat().engagement());assertEquals(SkillSpec.Kind.GATHER,SkillTools.kind("gather_resources"));
  var missing=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("crop");assertThrows(IllegalArgumentException.class,()->SkillSpec.parse(missing,SkillSpec.Kind.GATHER));
  var unlimited=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)unlimited).put("limit",0);assertThrows(IllegalArgumentException.class,()->SkillSpec.parse(unlimited,SkillSpec.Kind.GATHER));
 }
}
