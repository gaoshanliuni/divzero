package dev.mineagent.runtime.core.creature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CreatureRigTest {
 @Test void independentBoneTracksRetainRootAndParentHierarchy(){
  var d=CreatureDefinition.parse("""
  {"name":"walker","bones":[{"name":"body","pivot":[0,0,0]},{"name":"arm","parent":"body","pivot":[0.4,0.8,0]}],"animations":{"walk":{"duration_ticks":20,"bones":{"arm":[{"tick":0,"rotation":[-30,0,0]},{"tick":10,"rotation":[30,0,0]}]}}}}
  """);
  assertEquals("body",d.bones().get(1).parent());var clip=d.animations().get("walk");
  assertEquals(0,CreatureAnimation.sample(clip,10)[3]);assertEquals(30,CreatureAnimation.sampleBone(clip,"arm",10)[3]);assertEquals(0,CreatureAnimation.sampleBone(clip,"body",10)[3]);
  assertEquals(0,CreatureAnimation.sampleBone(clip,"arm",5)[3]);assertEquals(0,CreatureAnimation.sampleBone(clip,"arm",25)[3]);
 }
 @Test void rejectCyclesMissingParentsAndUnknownTracks(){
  assertThrows(IllegalArgumentException.class,()->CreatureDefinition.parse("""
  {"name":"bad","bones":[{"name":"arm","parent":"arm","pivot":[0,0,0]}]}
  """));
  assertThrows(IllegalArgumentException.class,()->CreatureDefinition.parse("""
  {"name":"bad","animations":{"idle":{"bones":{"missing":[{"tick":0}]}}}}
  """));
 }
}
