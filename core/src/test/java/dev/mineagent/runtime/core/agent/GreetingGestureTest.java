package dev.mineagent.runtime.core.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GreetingGestureTest {
    @Test void heldShiftDoesNotBecomeARepeatedGreeting(){var gesture=new GreetingGesture();for(int tick=0;tick<100;tick++)assertFalse(gesture.observe(tick,true));}
    @Test void threePressesTriggerAndOldPressesExpire(){var gesture=new GreetingGesture();assertFalse(gesture.observe(0,true));gesture.observe(4,false);assertFalse(gesture.observe(8,true));gesture.observe(12,false);assertTrue(gesture.observe(16,true));assertFalse(gesture.active(77));var slow=new GreetingGesture();slow.observe(0,true);slow.observe(40,false);slow.observe(80,true);slow.observe(90,false);assertFalse(slow.observe(110,true));}
    @Test void replyHasThreeDistinctCrouchEdgesAndFinishes(){int presses=0;boolean previous=false;for(int tick=0;tick<49;tick++){boolean value=GreetingGesture.replyCrouch(tick);if(value&&!previous)presses++;previous=value;}assertEquals(3,presses);assertFalse(previous);}
}
