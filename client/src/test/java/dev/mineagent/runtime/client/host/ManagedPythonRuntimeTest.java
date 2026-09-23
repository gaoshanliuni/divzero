package dev.mineagent.runtime.client.host;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class ManagedPythonRuntimeTest {
 @TempDir Path root;
 @Test void pathsAndArchiveIntegrityRejectBeforeExecution()throws Exception{for(String value:java.util.List.of("../outside","/absolute","python/../../outside","python/a:b","python/CON","python/a\\b"))assertThrows(java.io.IOException.class,()->ManagedPythonRuntime.safe(root,value));var f=root.resolve("wrong.gz");Files.writeString(f,"not Python");assertThrows(java.io.IOException.class,()->ManagedPythonRuntime.verifyArchive(f));}
 @Test void preparationRequiresLiveContext()throws Exception{org.junit.jupiter.api.Assumptions.assumeTrue(ManagedPythonRuntime.supported());assertThrows(java.io.IOException.class,()->new ManagedPythonRuntime(root).ensure(()->false));assertFalse(Files.exists(root.resolve("mineagent-host/environment.json")));}
 @Test void environmentSeesNoInterpreterOrCredentialOverrides(){var p=ManagedPythonRuntime.process(java.util.List.of("not-launched"),root);assertFalse(p.environment().containsKey("PYTHONPATH"));assertFalse(p.environment().containsKey("PYTHONHOME"));assertFalse(p.environment().containsKey("PIP_INDEX_URL"));assertEquals("1",p.environment().get("PYTHONUTF8"));}
}
