package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.Path;import java.time.Clock;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class ConversationStreamBatchTest {
 @TempDir Path dir;
 @Test void interleavedSessionsBulkReadAndCancellationRemainScoped()throws Exception{
  UUID owner=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
  try(var s=ConversationStore.open(dir.resolve("stream.db"),UUID.randomUUID(),Clock.systemUTC())){
   var ca=s.create(owner,a,UUID.randomUUID(),"A");var cb=s.create(owner,b,UUID.randomUUID(),"B");
   var ta=s.begin(owner,a,ca.conversationId(),UUID.randomUUID(),1,"hello A",0);var tb=s.begin(owner,b,cb.conversationId(),UUID.randomUUID(),1,"hello B",0);
   assertThrows(IllegalStateException.class,()->s.begin(owner,a,ca.conversationId(),UUID.randomUUID(),1,"must queue",0));
   for(int i=0;i<20;i++){assertTrue(s.streamDelta(ta.operationId(),"A","想",true,true));assertTrue(s.streamDelta(tb.operationId(),"B","think",true,true));}
   s.cancel(owner,a,ca.conversationId(),UUID.randomUUID(),ta.operationId());assertFalse(s.delta(ta.operationId(),"late"));assertTrue(s.streamDelta(tb.operationId(),"!","",true,false));
   var m=s.message(owner,b,cb.conversationId(),tb.assistantMessageId());var chunks=s.chunks(owner,b,cb.conversationId(),List.of(new ConversationStore.ChunkRequest(m.messageId(),m.revision(),0)),4096);
   assertEquals("B".repeat(20)+"!",chunks.getFirst().text());assertFalse(s.thinking(owner,b,cb.conversationId(),m.messageId()).active());
   assertThrows(SecurityException.class,()->s.chunks(owner,a,ca.conversationId(),List.of(new ConversationStore.ChunkRequest(m.messageId(),m.revision(),0)),4096));
   assertThrows(IllegalStateException.class,()->s.chunks(owner,b,cb.conversationId(),List.of(new ConversationStore.ChunkRequest(m.messageId(),m.revision()-1,0)),4096));
  }
 }
}
