package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SelfPlayScheduleTest {
    @Test void asymmetricConditionsAreMirroredAndBothControllersAlwaysHaveDifferentModels(){
        for(int wave=0;wave<8;wave+=2){var original=SelfPlaySchedule.wave(wave,3);var reverse=SelfPlaySchedule.wave(wave+1,3);
            assertEquals(5,original.size());for(int lane=0;lane<5;lane++){var a=original.get(lane);var b=reverse.get(lane);assertEquals(a.left(),b.right());assertEquals(a.right(),b.left());assertEquals(a.leftCount(),b.rightCount());assertEquals(a.rightCount(),b.leftCount());assertNotEquals(a.leftModel(),a.rightModel());assertEquals(a.leftModel(),b.leftModel());}}
        assertEquals(3,SelfPlaySchedule.wave(0,2).get(2).rightCount());assertEquals(5,SelfPlaySchedule.wave(2,2).get(2).rightCount());
    }
}
