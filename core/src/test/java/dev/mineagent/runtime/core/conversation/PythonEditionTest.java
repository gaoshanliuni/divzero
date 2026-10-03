package dev.mineagent.runtime.core.conversation;
import dev.mineagent.runtime.core.hostsupport.PythonEdition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PythonEditionTest {
    @Test void packagedEditionChangesBothInstructionsAndActualToolCatalog(){
        assertNotNull(PythonEdition.class.getResource("/META-INF/divzero/edition.properties"));
        var session=new CapabilitySession();session.preload("请用Python读取本机文件",java.util.List.of());
        assertEquals(PythonEdition.bundled(),session.tools().contains("python_execute"));assertEquals(PythonEdition.bundled(),ConversationTools.NAMES.contains("python_install_packages"));
        if(!PythonEdition.bundled()){assertTrue(ConversationTools.instructions().contains("此版本不支持Python"));assertEquals("PYTHON_UNSUPPORTED_EDITION",session.load("host").get("error"));assertEquals("NOT_STARTED",session.admission("python_execute").orElseThrow().get("executionState"));}
    }
}
