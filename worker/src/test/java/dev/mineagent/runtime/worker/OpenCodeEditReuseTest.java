package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.scripting.opencode.OpenCodeRuntime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OpenCodeEditReuseTest {
    @Test void exactAndIndentationEditsKeepTheUntouchedContentAndOriginalLineEndings(){
        var result=OpenCodeRuntime.edit("keep\r\n  first();\r\n  second();\r\nend\r\n","first();\nsecond();","  repaired();",false);
        assertTrue(result.accepted(),result.error());assertEquals("keep\r\n  repaired();\r\nend\r\n",result.source());
    }
    @Test void ambiguousEmptyAndUnmatchedEditsDoNotRewriteACandidate(){
        for(String old:new String[]{"x","","missing"}){var result=OpenCodeRuntime.edit("x\nx\n",old,"changed",false);assertFalse(result.accepted());assertEquals("x\nx\n",result.source());}
    }
    @Test void explicitAllTreatsReplacementAsLiteralSource(){
        assertEquals("$&\n$&",OpenCodeRuntime.edit("x\nx","x","$&",true).source());
    }
}
