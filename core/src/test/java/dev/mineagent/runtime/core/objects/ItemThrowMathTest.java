package dev.mineagent.runtime.core.objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ItemThrowMathTest {
    @Test void serverTicksDetermineBoundedCharge(){assertEquals(8,ItemThrowMath.usedTicks(1192));assertEquals(.2,ItemThrowMath.charge(8,40));assertEquals(1,ItemThrowMath.charge(90,40));assertThrows(IllegalArgumentException.class,()->ItemThrowMath.usedTicks(-1));assertThrows(IllegalArgumentException.class,()->ItemThrowMath.charge(8,0));assertThrows(IllegalArgumentException.class,()->ItemThrowMath.charge(8,201));}
    @Test void launchIsDirectionalAndRejectsInventedSpeeds(){var shortThrow=ItemThrowMath.launch(0,0,1,.5,.1);var longThrow=ItemThrowMath.launch(0,0,1,1.5,.1);assertEquals(.5,shortThrow.z());assertEquals(1.5,longThrow.z());assertEquals(.1,longThrow.y());assertThrows(IllegalArgumentException.class,()->ItemThrowMath.launch(0,0,1,Double.NaN,0));assertThrows(IllegalArgumentException.class,()->ItemThrowMath.launch(0,0,1,3.1,0));assertThrows(IllegalArgumentException.class,()->ItemThrowMath.launch(0,0,0,1,0));assertThrows(IllegalArgumentException.class,()->ItemThrowMath.launch(0,0,1,1,2));}
}
