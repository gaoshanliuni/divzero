package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RangedContactEscapeTest {
    @Test void closesOnceThenEscapesUntilThereIsRoomToCharge(){var s=new RangedContactEscape();assertTrue(s.update(0,2,true,true,false,false));assertTrue(s.update(1,5,false,true,false,false));assertTrue(s.update(2,8,false,true,false,false));assertTrue(s.update(4,7,false,true,false,false));assertTrue(s.update(5,9,false,true,false,false));assertFalse(s.update(9,9,false,true,false,false));}
    @Test void realMeleeAndAlreadyLoadedShotRemainUsable(){var s=new RangedContactEscape();assertFalse(s.update(0,2,true,true,true,false));assertFalse(s.update(1,2,true,true,false,true));assertTrue(s.update(2,2,true,true,false,false));assertFalse(s.update(3,2,true,true,true,false));}
}
