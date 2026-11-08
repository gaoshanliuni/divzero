package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ConversationSummaryBacklogTest {
 @TempDir Path root;
 @Test void intentSurvivesRestartAndOldCompletionCannotEraseNewerTarget()throws Exception{
  var world=UUID.randomUUID();var viewer=UUID.randomUUID();var agent=UUID.randomUUID();var conversation=UUID.randomUUID();var clock=Clock.fixed(Instant.ofEpochMilli(100000),ZoneOffset.UTC);
  try(var queue=new ConversationSummaryBacklog(root.resolve("db"),world,clock)){queue.enqueue(viewer,agent,conversation,12,8192);queue.enqueue(viewer,agent,conversation,20,16384);assertEquals(20,queue.due(8).getFirst().target());queue.covered(viewer,agent,conversation,12);assertEquals(1,queue.due(8).size());queue.defer(viewer,agent,conversation,30000,true);assertTrue(queue.due(8).isEmpty());}
  try(var queue=new ConversationSummaryBacklog(root.resolve("db"),world,Clock.offset(clock,Duration.ofSeconds(31)))){var work=queue.due(8).getFirst();assertEquals(20,work.target());assertEquals(16384,work.outputBudget());assertEquals(1,work.failures());queue.covered(viewer,agent,conversation,20);assertTrue(queue.due(8).isEmpty());}
  try(var queue=new ConversationSummaryBacklog(root.resolve("db"),UUID.randomUUID(),clock)){assertTrue(queue.due(8).isEmpty());}
 }
}
