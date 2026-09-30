package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.scripting.opencode.ExecutionProgress;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionProgressTest {
    @Test void repeatsRequireSameArgumentsOutcomeAndContextAndDoNotCountTotalRounds(){
        var progress=new ExecutionProgress();
        for(int i=0;i<100;i++)assertFalse(progress.observe("read_file","{\"offset\":"+i+"}",Map.of("status","OBSERVED","nextOffset",i+1),"world",true).warn());
        for(int i=0;i<2;i++)assertFalse(progress.observe("read_file","{}",Map.of("status","READ_FAILED","error","MISSING","observedAt",i),"world",true).warn());
        assertTrue(progress.observe("read_file","{}",Map.of("status","READ_FAILED","error","MISSING","observedAt",9),"world",true).warn());
        assertTrue(progress.observe("read_file","{}",Map.of("status","READ_FAILED","error","MISSING","observedAt",10),"world",true).blocked());
        assertFalse(progress.observe("read_file","{}",Map.of("status","OBSERVED","revision",2),"world",true).warn());
        assertFalse(progress.observe("read_file","{}",Map.of("status","READ_FAILED","error","MISSING"),"new world",true).warn());
        for(int i=0;i<10;i++)assertFalse(progress.observe("attack","{}",Map.of("status","APPLIED"),"world",false).warn());
    }
    @Test void alternatingUnchangedReadsCannotHideAStall(){
        var guard=new ExecutionProgress();
        for(int i=0;i<3;i++){
            var result=guard.observe("web_search","{}",Map.of("status","READ_FAILED","error","CAPTCHA","fetchedAt",i),"same world",true);
            assertEquals(i==2,result.warn());guard.observe("inspect_registry","{}",Map.of("status","OBSERVED","version",1),"same world",true);
        }
        assertTrue(guard.observe("web_search","{}",Map.of("status","READ_FAILED","error","CAPTCHA","fetchedAt",99),"same world",true).blocked());
        assertFalse(guard.observe("web_search","{}",Map.of("status","OBSERVED","results",List.of("new source")),"same world",true).warn());
    }
}
