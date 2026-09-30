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
    @Test void failedCandidateSurvivesWithoutReplacingTheRunningDefinition()throws Exception{
        var scope=new NativeUiStore.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var db=directory.resolve("failed-drafts.db");UUID draftId=UUID.randomUUID();
        try(var store=new NativeUiStore(db)){store.save(scope,"quest",0,"minecraft:overworld",SOURCE,Map.of("points",IntNode.valueOf(7)),true);store.failed(scope,"quest",draftId,1,"{broken","line 1 column 8: missing closing brace");assertEquals(1,store.get(scope,"quest").orElseThrow().revision());assertTrue(store.pending(scope,"quest").isEmpty());}
        try(var store=new NativeUiStore(db)){var draft=store.failed(scope,"quest",draftId).orElseThrow();assertEquals("{broken",draft.source());assertEquals(1,draft.baseRevision());assertTrue(draft.diagnostic().contains("column 8"));assertTrue(store.failed(new NativeUiStore.Scope(scope.world(),UUID.randomUUID(),scope.agent()),"quest",draftId).isEmpty());assertEquals(7,store.get(scope,"quest").orElseThrow().data().get("points").intValue());}
    }
    @Test void revisionsSurviveRestartAndSeparateWorldOwnerAndAgent()throws Exception{
        var scope=new NativeUiStore.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var db=directory.resolve("ui.db");
        try(var a=new NativeUiStore(db);var b=new NativeUiStore(db)){
            assertEquals(1,a.save(scope,"quest",0,"minecraft:overworld",SOURCE,Map.of("points",IntNode.valueOf(3)),true).revision());
            assertEquals(3,b.get(scope,"quest").orElseThrow().data().get("points").intValue());
            assertEquals(scope.agent(),b.owned(scope.world(),scope.owner()).getFirst().agent());assertTrue(b.owned(scope.world(),UUID.randomUUID()).isEmpty());
            assertThrows(IllegalStateException.class,()->b.save(scope,"quest",0,"minecraft:overworld",SOURCE,Map.of(),true));
            assertTrue(b.list(new NativeUiStore.Scope(scope.world(),scope.owner(),UUID.randomUUID())).isEmpty());
            assertTrue(b.get(new NativeUiStore.Scope(UUID.randomUUID(),scope.owner(),scope.agent()),"quest").isEmpty());
            assertThrows(IllegalArgumentException.class,()->a.save(scope,"wrong",1,"minecraft:overworld",SOURCE,Map.of(),true));
        }
        try(var a=new NativeUiStore(db)){assertEquals(1,a.get(scope,"quest").orElseThrow().revision());assertEquals("HUD",a.list(scope).getFirst().get("surface"));}
    }
    @Test void nativeUiToolsAreDeclaredAndReadsDoNotMutate(){assertFalse(dev.mineagent.runtime.core.conversation.ConversationTools.mutation("inspect_native_ui"));for(var name:List.of("set_native_ui","patch_native_ui_data","control_native_ui"))assertTrue(dev.mineagent.runtime.core.conversation.ConversationTools.NAMES.contains(name));}
    @Test void uncertainActivationCanBeReconciledAfterStoreReopensWithoutReplaying()throws Exception{
        var scope=new NativeUiStore.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var db=directory.resolve("candidate.db");UUID token;
        try(var store=new NativeUiStore(db)){token=store.stage(scope,"quest",0,"minecraft:overworld",SOURCE).token();assertTrue(store.get(scope,"quest").isEmpty());}
        try(var store=new NativeUiStore(db)){
            assertEquals(token,store.pending(scope,"quest").orElseThrow().token());
            assertThrows(IllegalStateException.class,()->store.stage(scope,"quest",0,"minecraft:overworld",SOURCE));
            assertThrows(IllegalStateException.class,()->store.acknowledge(scope,"quest",UUID.randomUUID(),1,Map.of(),true));
            assertEquals(1,store.acknowledge(scope,"quest",token,1,Map.of("points",IntNode.valueOf(7)),true).revision());
            assertTrue(store.pending(scope,"quest").isEmpty());assertEquals(7,store.get(scope,"quest").orElseThrow().data().get("points").intValue());
            var next=store.stage(scope,"quest",1,"minecraft:overworld",SOURCE);store.discard(scope,"quest",next.token());assertEquals(1,store.get(scope,"quest").orElseThrow().revision());
            assertDoesNotThrow(()->store.stage(scope,"quest",1,"minecraft:overworld",SOURCE));
        }
    }
}
