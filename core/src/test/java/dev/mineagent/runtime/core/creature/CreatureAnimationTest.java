package dev.mineagent.runtime.core.creature;
import org.junit.jupiter.api.Test;import com.fasterxml.jackson.databind.ObjectMapper;import static org.junit.jupiter.api.Assertions.*;
class CreatureAnimationTest {
 @Test void interpolateLoopAndHurtClamp()throws Exception{var clip=CreatureAnimation.parse(new ObjectMapper().readTree("{\"idle\":{\"duration_ticks\":20,\"keyframes\":[{\"tick\":0},{\"tick\":10,\"translation\":[0,1,0]}]}}")).get("idle");assertEquals(.5,CreatureAnimation.sample(clip,5)[1]);assertEquals(.5,CreatureAnimation.sample(clip,25)[1]);assertEquals(1,CreatureAnimation.sample(clip,5)[6]);}
 @Test void rejectBadFramesAndUnknownEvents()throws Exception{var json=new ObjectMapper();assertThrows(IllegalArgumentException.class,()->CreatureAnimation.parse(json.readTree("{\"bad\":{}}")));assertThrows(IllegalArgumentException.class,()->CreatureAnimation.parse(json.readTree("{\"hurt\":{\"keyframes\":[{\"tick\":2},{\"tick\":1}]}}")));}
}
