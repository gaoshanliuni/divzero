package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ConversationTitleTest {
    @TempDir Path dir;
    @Test void automaticTitleIsOneShotAndPersisted()throws Exception{
        UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),id;Path db=dir.resolve("auto.db");
        try(var store=ConversationStore.open(db,world,Clock.systemUTC())){
            var c=store.create(owner,agent,UUID.randomUUID(),"新的对话");id=c.conversationId();store.requestAutomaticTitle(owner,agent,id);var op=UUID.randomUUID();assertFalse(store.claimAutomaticTitle(owner,agent,id,op));
            var turn=store.begin(owner,agent,id,UUID.randomUUID(),c.revision(),"请建一座灯塔",0);store.finish(turn.operationId(),"COMPLETE","开始规划灯塔","");assertTrue(store.claimAutomaticTitle(owner,agent,id,op));assertFalse(store.claimAutomaticTitle(owner,agent,id,UUID.randomUUID()));
            assertTrue(store.finishAutomaticTitle(owner,agent,id,op,"海边灯塔","{}"));assertEquals("海边灯塔",store.get(owner,agent,id).title());assertFalse(store.finishAutomaticTitle(owner,agent,id,op,"不能重复覆盖","{}"));
        }
        try(var store=ConversationStore.open(db,world,Clock.systemUTC())){assertEquals("海边灯塔",store.get(owner,agent,id).title());assertFalse(store.claimAutomaticTitle(owner,agent,id,UUID.randomUUID()));}
    }
    @Test void playerRenameWinsAgainstLateModelResponse()throws Exception{
        UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID();
        try(var store=ConversationStore.open(dir.resolve("manual.db"),world,Clock.systemUTC())){
            var c=store.create(owner,agent,UUID.randomUUID(),"新的对话");var id=c.conversationId();store.requestAutomaticTitle(owner,agent,id);var turn=store.begin(owner,agent,id,UUID.randomUUID(),c.revision(),"prompt",0);store.finish(turn.operationId(),"COMPLETE","reply","");var op=UUID.randomUUID();assertTrue(store.claimAutomaticTitle(owner,agent,id,op));
            store.change(owner,agent,id,UUID.randomUUID(),store.get(owner,agent,id).revision(),"rename","玩家自己的标题");assertFalse(store.finishAutomaticTitle(owner,agent,id,op,"迟到的模型标题","{}"));assertEquals("玩家自己的标题",store.get(owner,agent,id).title());
        }
    }
}
