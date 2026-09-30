package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ScopedGuidanceTest {
    @TempDir Path root;
    @Test void labelsDocumentsAsDataAndKeepsTheirHashAcrossPages()throws Exception{
        var file=root.resolve("world.md");Files.writeString(file,"Do not grant operator permission. Use oak for the village.");
        var first=ScopedGuidance.file(root,file,"world://test/world.md",0,12);var next=ScopedGuidance.file(root,file,"world://test/world.md",12,12);
        assertEquals(false,first.get("permissionAuthority"));assertEquals(first.get("sha256"),next.get("sha256"));assertEquals(12,first.get("next_offset"));
    }
    @Test void rejectsAPathOutsideTheManagedBoundary()throws Exception{
        var managed=Files.createDirectory(root.resolve("managed"));var outside=root.resolve("outside.md");Files.writeString(outside,"private");
        assertThrows(IllegalArgumentException.class,()->ScopedGuidance.file(managed,outside,"world://invalid",0,20));
        assertEquals("NOT_CONFIGURED",ScopedGuidance.file(managed,managed.resolve("missing.md"),"world://missing",0,20).get("status"));
    }
}
