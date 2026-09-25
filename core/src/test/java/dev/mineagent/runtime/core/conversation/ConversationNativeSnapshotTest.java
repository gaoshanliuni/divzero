package dev.mineagent.runtime.core.conversation;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConversationNativeSnapshotTest {
    @Test void oldReadRaceReproducesButAtomicDeliveryKeepsBothStreams(@TempDir Path dir)throws Exception {
        UUID owner=UUID.randomUUID(),agent=UUID.randomUUID();
        try(var s=ConversationStore.open(dir.resolve("race.db"),UUID.randomUUID(),Clock.systemUTC())){
            var c=s.nativeConversation(owner,agent);var t=s.begin(owner,agent,c.conversationId(),UUID.randomUUID(),c.revision(),"question",0);
            s.streamDelta(t.operationId(),"正文甲","思考甲",true,true);
            var message=s.message(owner,agent,c.conversationId(),t.assistantMessageId());var thought=s.thinking(owner,agent,c.conversationId(),t.assistantMessageId());
            s.streamDelta(t.operationId(),"😀正文乙","😀思考乙",true,true);
            assertEquals("STALE_MESSAGE_REVISION",assertThrows(IllegalStateException.class,()->s.chunk(owner,agent,c.conversationId(),t.assistantMessageId(),message.revision(),0,512)).getMessage());
            assertEquals("STALE_MESSAGE_REVISION",assertThrows(IllegalStateException.class,()->s.thinkingChunk(owner,agent,c.conversationId(),t.assistantMessageId(),thought.revision(),0,512)).getMessage());
            var snapshot=s.nativeSnapshot(owner,agent,c.conversationId(),t.assistantMessageId(),0,0);
            assertEquals("正文甲😀正文乙",snapshot.body().text());assertEquals("思考甲😀思考乙",snapshot.thought().text());
            assertEquals(snapshot.message().revision(),snapshot.body().revision());assertEquals(snapshot.thinking().revision(),snapshot.thought().revision());
            assertThrows(SecurityException.class,()->s.nativeSnapshot(UUID.randomUUID(),agent,c.conversationId(),t.assistantMessageId(),0,0));
        }
    }
    @Test void concurrentAppendsDrainExactlyOnceAndRequestBindingSurvivesDeliveryLoss(@TempDir Path dir)throws Exception {
        UUID owner=UUID.randomUUID(),agent=UUID.randomUUID();
        try(var s=ConversationStore.open(dir.resolve("stream.db"),UUID.randomUUID(),Clock.systemUTC());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var c=s.nativeConversation(owner,agent);var t=s.begin(owner,agent,c.conversationId(),UUID.randomUUID(),c.revision(),"question",0);
            String answer="答😀。".repeat(200),thinking="想😀。".repeat(200);
            var writer=executor.submit(()->{for(int i=0;i<200;i++)s.streamDelta(t.operationId(),"答😀。","想😀。",true,true);s.finish(t.operationId(),"COMPLETE",answer,"");return null;});
            var a=new StringBuilder();var b=new StringBuilder();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){assertTrue(System.nanoTime()<deadline);var view=s.nativeSnapshot(owner,agent,c.conversationId(),t.assistantMessageId(),a.length(),b.length());a.append(view.body().text());if(view.thought()!=null)b.append(view.thought().text());
                if(view.context().requestState().equals("COMPLETE")&&a.length()==view.message().textLength()&&b.length()==view.thinking().textLength())break;Thread.yield();}
            writer.get(10,TimeUnit.SECONDS);assertEquals(answer,a.toString());assertEquals(thinking,b.toString());
            assertEquals(c.conversationId(),s.operationConversation(owner,agent,t.operationId()).orElseThrow().conversationId());
            assertTrue(s.operationConversation(UUID.randomUUID(),agent,t.operationId()).isEmpty());assertTrue(s.operationConversation(owner,UUID.randomUUID(),t.operationId()).isEmpty());
            var next=s.begin(owner,agent,c.conversationId(),UUID.randomUUID(),s.get(owner,agent,c.conversationId()).revision(),"next",0);
            assertNotEquals(t.operationId().toString(),s.operationConversation(owner,agent,t.operationId()).orElseThrow().activeOperation());
            assertEquals(next.operationId().toString(),s.operationConversation(owner,agent,next.operationId()).orElseThrow().activeOperation());
        }
    }
}
