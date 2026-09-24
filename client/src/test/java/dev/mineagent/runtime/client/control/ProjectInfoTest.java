package dev.mineagent.runtime.client.control;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ProjectInfoTest {
 @Test void identityIsPublicAndVersionIsSupplied(){var info=new ProjectInfo("4.2-beta");assertEquals("4.2-beta",info.fields().get("version"));assertEquals("DivZero",info.fields().get("projectName"));assertEquals("gaoshanliuni",info.fields().get("developer"));assertEquals("https://github.com/gaoshanliuni/divzero",info.fields().get("projectUrl"));assertEquals(4,info.fields().size());}
 @Test void missingOrControlCharacterVersionRejected(){assertThrows(IllegalArgumentException.class,()->new ProjectInfo(""));assertThrows(IllegalArgumentException.class,()->new ProjectInfo("v1\nvalue"));}
}
