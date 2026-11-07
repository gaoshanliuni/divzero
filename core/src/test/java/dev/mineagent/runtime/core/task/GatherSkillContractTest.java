package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GatherSkillContractTest {
 @Test void requiresExplicitResourceCountAndAreaButPreservesCombatPolicy()throws Exception{
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var input=json.readTree("{\"id\":\"wood\",\"dimension\":\"minecraft:overworld\",\"min\":[0,64,0],\"max\":[12,72,12],\"crop\":\"minecraft:oak_log\",\"limit\":32}");
  var spec=SkillSpec.parse(input,SkillSpec.Kind.GATHER);assertEquals(SkillSpec.Kind.GATHER,spec.kind());assertEquals(32,spec.limit());assertEquals(CombatPolicy.Engagement.SELF_DEFENSE,spec.combat().engagement());assertEquals(SkillSpec.Kind.GATHER,SkillTools.kind("gather_resources"));
  var missing=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("crop");assertThrows(IllegalArgumentException.class,()->SkillSpec.parse(missing,SkillSpec.Kind.GATHER));
  var unlimited=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)unlimited).put("limit",0);assertThrows(IllegalArgumentException.class,()->SkillSpec.parse(unlimited,SkillSpec.Kind.GATHER));
 }
}
