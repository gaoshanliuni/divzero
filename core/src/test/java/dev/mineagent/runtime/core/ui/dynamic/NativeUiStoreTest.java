package dev.mineagent.runtime.core.ui.dynamic;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeUiStoreTest {
    @TempDir Path directory;
    static final String SOURCE="""
        {"id":"quest","title":"Quest","surface":"HUD","root":{"id":"root","type":"column","children":[{"id":"score","type":"label","bind":"points"}]},"data":{"points":1}}
        """;
    @Test void revisionsSurviveRestartAndSeparateWorldOwnerAndAgent()throws Exception{
        var scope=new NativeUiStore.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var db=directory.resolve("ui.db");
        try(var a=new NativeUiStore(db);var b=new NativeUiStore(db)){
            assertEquals(1,a.save(scope,"quest",0,"minecraft:overworld",SOURCE,Map.of("points",IntNode.valueOf(3)),true).revision());
            assertEquals(3,b.get(scope,"quest").orElseThrow().data().get("points").intValue());
            assertThrows(IllegalStateException.class,()->b.save(scope,"quest",0,"minecraft:overworld",SOURCE,Map.of(),true));
            assertTrue(b.list(new NativeUiStore.Scope(scope.world(),scope.owner(),UUID.randomUUID())).isEmpty());
            assertTrue(b.get(new NativeUiStore.Scope(UUID.randomUUID(),scope.owner(),scope.agent()),"quest").isEmpty());
            assertThrows(IllegalArgumentException.class,()->a.save(scope,"wrong",1,"minecraft:overworld",SOURCE,Map.of(),true));
        }
        try(var a=new NativeUiStore(db)){assertEquals(1,a.get(scope,"quest").orElseThrow().revision());assertEquals("HUD",a.list(scope).getFirst().get("surface"));}
    }
    @Test void nativeUiToolsAreDeclaredAndReadsDoNotMutate(){assertFalse(dev.mineagent.runtime.core.conversation.ConversationTools.mutation("inspect_native_ui"));for(var name:List.of("set_native_ui","patch_native_ui_data","control_native_ui"))assertTrue(dev.mineagent.runtime.core.conversation.ConversationTools.NAMES.contains(name));}
}
