package dev.mineagent.runtime.core.objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InstanceScoreLedgerTest {
    @Test void replayProjectsCurrentTotalWithoutAddingOrRollingBack(){var first=InstanceScoreLedger.parse(null).award("flight1","player",2);var second=first.award("flight2","player",2);assertSame(second,second.award("flight1","player",2));assertEquals(4,second.scores().get("player"));var restored=InstanceScoreLedger.parse(second.encode());assertEquals(second,restored.award("flight1","player",2));assertEquals(4,restored.scores().get("player"));assertThrows(IllegalArgumentException.class,()->restored.award("flight1","other",2));assertThrows(IllegalArgumentException.class,()->restored.award("flight1","player",3));}
    @Test void rejectsCapacityRatherThanDroppingDedupHistory(){var v=InstanceScoreLedger.parse(null);for(int i=0;i<512;i++)v=v.award("event"+i,"player",2);var full=v;assertThrows(IllegalStateException.class,()->full.award("new","player",2));assertSame(full,full.award("event1","player",2));assertEquals(1024,full.scores().get("player"));assertThrows(IllegalArgumentException.class,()->full.award("bad","player",0));assertThrows(IllegalStateException.class,()->InstanceScoreLedger.parse("invalid"));}
}
