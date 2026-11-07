package dev.mineagent.runtime.core.host;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PythonPathsTest {
    @TempDir Path root;
    @Test void existingOwnerFileRestoresInstallationAndDifferentInstancesRemainIsolated()throws Exception{
        String before=System.getProperty("divzero.pythonRuntimeRoot");
        try{
            Path base=Files.createDirectory(root.resolve("runtime")),game=Files.createDirectory(root.resolve("game"));
            System.setProperty("divzero.pythonRuntimeRoot",base.toString());Path old=Files.createDirectory(base.resolve("a1b2c3"));Files.writeString(old.resolve("game.owner"),game.toRealPath().toString());
            assertEquals(old.toRealPath(),PythonPaths.runtimeRoot(game));assertEquals(old.toRealPath(),PythonPaths.runtimeRoot(game));PythonPaths.prepare(game,false);
            Path other=Files.createDirectory(root.resolve("other"));assertNotEquals(old,PythonPaths.runtimeRoot(other));PythonPaths.prepare(other,true);
            assertTrue(Files.readString(game.resolve("mineagent-host/runtime-locations-v2.json")).contains("a1b2c3"));
        }finally{if(before==null)System.clearProperty("divzero.pythonRuntimeRoot");else System.setProperty("divzero.pythonRuntimeRoot",before);}
    }
    @Test void storedLocationCannotEscapeItsOwnedRuntimeBase()throws Exception{
        String before=System.getProperty("divzero.pythonRuntimeRoot");
        try{
            Path base=Files.createDirectory(root.resolve("runtime")),game=Files.createDirectory(root.resolve("game")),workspace=Files.createDirectory(game.resolve("mineagent-host"));System.setProperty("divzero.pythonRuntimeRoot",base.toString());
            Files.writeString(workspace.resolve("runtime-locations-v2.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(base.toRealPath().toString(),"../foreign")));
            assertThrows(IllegalStateException.class,()->PythonPaths.runtimeRoot(game));
        }finally{if(before==null)System.clearProperty("divzero.pythonRuntimeRoot");else System.setProperty("divzero.pythonRuntimeRoot",before);}
    }
}
