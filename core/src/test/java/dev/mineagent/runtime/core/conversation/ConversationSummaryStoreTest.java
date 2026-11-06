package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationSummaryStoreTest {
    @TempDir Path dir;final UUID world=UUID.randomUUID(),viewer=UUID.randomUUID(),agent=UUID.randomUUID();
    @Test void sourceChunkEndingAt4096NeverSplitsASurrogatePair()throws Exception{
        Path db=dir.resolve("db");String original="x".repeat(4095)+"😀tail";
        try(var m=ConversationStore.open(db,world,Clock.systemUTC());var s=ConversationSummaryStore.open(db,world,Clock.systemUTC())){
            var c=m.create(viewer,agent,UUID.randomUUID(),"Unicode boundary");var t=m.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,original,0);m.finish(t.operationId(),"COMPLETE","reply","");
            UUID request=UUID.randomUUID();var first=s.prepare(m,viewer,agent,c.conversationId(),request,0,2,16384,128,null);
            assertEquals(new ConversationSummaryStore.Cursor(1,4095),first.summary().end(),"4096-unit source chunk must leave the entire emoji for the next batch");
            var previous=s.complete(m,first.summaryId(),"{\"summary\":\"first\"}","fixture","fixture");
            var second=s.prepare(m,viewer,agent,c.conversationId(),request,1,2,16384,128,previous);
            assertEquals(first.summary().end(),second.summary().start());assertEquals(new ConversationSummaryStore.Cursor(3,0),second.summary().end());
            var ref=second.summary().sources().getFirst();String rest=m.chunk(viewer,agent,c.conversationId(),t.userMessageId(),ref.revision(),ref.from(),4096).text();
            assertEquals("😀tail",rest);assertFalse(second.prompt().contains("😀tail"),"user source remains in the archive, not assistant-prose summary input");assertTrue(second.prompt().contains("reply"));
            var ready=s.complete(m,second.summaryId(),"{\"summary\":\"complete\"}","fixture","fixture");assertTrue(s.valid(m,viewer,agent,ready));
            assertEquals(original,m.chunk(viewer,agent,c.conversationId(),t.userMessageId(),1,0,4096).text()+m.chunk(viewer,agent,c.conversationId(),t.userMessageId(),1,4096,4096).text());
        }
    }
    @Test void persistedLegacySplitCursorCannotBecomeReady()throws Exception{
        Path db=dir.resolve("db");String original="x".repeat(4095)+"😀tail";
        try(var m=ConversationStore.open(db,world,Clock.systemUTC());var s=ConversationSummaryStore.open(db,world,Clock.systemUTC())){
            var c=m.create(viewer,agent,UUID.randomUUID(),"Legacy boundary");var t=m.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,original,0);m.finish(t.operationId(),"COMPLETE","reply","");
            var prepared=s.prepare(m,viewer,agent,c.conversationId(),UUID.randomUUID(),0,2,16384,128,null);var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var ref=prepared.summary().sources().getFirst();var legacyRef=new ConversationSummaryStore.Source(ref.messageId(),ref.sequence(),ref.revision(),ref.role(),ref.status(),ref.errorCode(),0,4096,sha(original.substring(0,4096)));
            var legacy=json.valueToTree(prepared.summary());((com.fasterxml.jackson.databind.node.ObjectNode)legacy).set("sources",json.valueToTree(List.of(legacyRef)));((com.fasterxml.jackson.databind.node.ObjectNode)legacy.path("end")).put("offset",4096);
            ((com.fasterxml.jackson.databind.node.ObjectNode)legacy).put("sourceHash",sha(json.writeValueAsString(Arrays.asList(null,"",List.of(legacyRef)))));
            try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var update=connection.prepareStatement("UPDATE mineagent_conversation_summaries_v1 SET payload=? WHERE id=?")){update.setString(1,legacy.toString());update.setString(2,prepared.summaryId().toString());assertEquals(1,update.executeUpdate());}
            var failure=assertThrows(IllegalArgumentException.class,()->s.complete(m,prepared.summaryId(),"{\"summary\":\"split\"}","fixture","fixture"));assertEquals("SUMMARY_SOURCE_CHANGED",failure.getMessage());assertEquals("FAILED",s.get(viewer,agent,c.conversationId(),prepared.summaryId()).state());
        }
    }
    private static String sha(String text)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Test void boundedBatchesHaveVersionedSourceRangesAndNeverReplaceOriginals()throws Exception{
        Path db=dir.resolve("db");try(var messages=ConversationStore.open(db,world,Clock.systemUTC());var summaries=ConversationSummaryStore.open(db,world,Clock.systemUTC())){
            var c=messages.create(viewer,agent,UUID.randomUUID(),"A");var t=messages.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"😀原文".repeat(900),0);messages.finish(t.operationId(),"COMPLETE","reply","");
            UUID request=UUID.randomUUID();ConversationSummaryStore.Summary prior=null;int batches=0;
            while(prior==null||prior.endSequence()!=3||prior.endOffset()!=0){
                var job=summaries.prepare(messages,viewer,agent,c.conversationId(),request,batches++,2,2048,256,prior);assertTrue(job.dispatch());assertTrue(job.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2048);
                assertTrue(job.endSequence()>job.startSequence()||job.endOffset()>job.startOffset());
                prior=summaries.complete(messages,job.summaryId(),"{\"summary\":\"可追溯摘要\"}","fixture","test-model");assertEquals("READY",prior.state());assertTrue(summaries.valid(messages,viewer,agent,prior));assertTrue(batches<30);
            }
            assertEquals(2,messages.get(viewer,agent,c.conversationId()).messageCount());assertTrue(summaries.latest(messages,viewer,agent,c.conversationId(),2,256).isPresent());
            final var result=prior;assertThrows(SecurityException.class,()->summaries.get(UUID.randomUUID(),agent,c.conversationId(),result.summaryId()));
        }
    }
    @Test void pendingRestartAndDuplicateIdentityNeverAutomaticallyDispatchAgain()throws Exception{
        Path db=dir.resolve("db");UUID id,jobId,op=UUID.randomUUID();
        try(var m=ConversationStore.open(db,world,Clock.systemUTC());var s=ConversationSummaryStore.open(db,world,Clock.systemUTC())){id=m.create(viewer,agent,UUID.randomUUID(),"A").conversationId();var t=m.begin(viewer,agent,id,UUID.randomUUID(),1,"history",0);m.finish(t.operationId(),"COMPLETE","response","");var j=s.prepare(m,viewer,agent,id,op,0,2,2048,256,null);jobId=j.summaryId();assertFalse(s.prepare(m,viewer,agent,id,op,0,2,2048,256,null).dispatch());}
        try(var m=ConversationStore.open(db,world,Clock.systemUTC());var s=ConversationSummaryStore.open(db,world,Clock.systemUTC())){assertEquals("INTERRUPTED",s.get(viewer,agent,id,jobId).state());assertFalse(s.prepare(m,viewer,agent,id,op,0,2,2048,256,null).dispatch());assertThrows(IllegalStateException.class,()->s.complete(m,jobId,"{\"summary\":\"late\"}","p","m"));}
    }
    @Test void invalidOutputAndChangedSourceAreNotUsableSummaries()throws Exception{
        Path db=dir.resolve("db");try(var m=ConversationStore.open(db,world,Clock.systemUTC());var s=ConversationSummaryStore.open(db,world,Clock.systemUTC())){
            var c=m.create(viewer,agent,UUID.randomUUID(),"A");var t=m.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"raw",0);m.finish(t.operationId(),"COMPLETE","reply","");
            var bad=s.prepare(m,viewer,agent,c.conversationId(),UUID.randomUUID(),0,2,2048,128,null);assertThrows(IllegalArgumentException.class,()->s.complete(m,bad.summaryId(),"{\"summary\":\""+"x".repeat(129)+"\"}","p","m"));assertEquals("FAILED",s.get(viewer,agent,c.conversationId(),bad.summaryId()).state());
            var good=s.prepare(m,viewer,agent,c.conversationId(),UUID.randomUUID(),0,2,2048,128,null);var ready=s.complete(m,good.summaryId(),"{\"summary\":\"short\"}","p","m");
            try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var update=connection.prepareStatement("UPDATE mineagent_conversation_messages_v1 SET text='changed',revision=revision+1 WHERE id=?")){update.setString(1,t.userMessageId().toString());update.executeUpdate();}
            assertFalse(s.valid(m,viewer,agent,ready));assertTrue(s.latest(m,viewer,agent,c.conversationId(),2,128).isEmpty());
        }
    }
    @Test void duplicateJsonFieldsAndFailedRequestRetriesCannotMasqueradeAsReady()throws Exception{
        Path db=dir.resolve("db");try(var m=ConversationStore.open(db,world,Clock.systemUTC());var s=ConversationSummaryStore.open(db,world,Clock.systemUTC())){
            var c=m.create(viewer,agent,UUID.randomUUID(),"A");var t=m.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"raw",0);m.finish(t.operationId(),"COMPLETE","reply","");UUID request=UUID.randomUUID();var job=s.prepare(m,viewer,agent,c.conversationId(),request,0,2,2048,128,null);
            var failure=assertThrows(IllegalArgumentException.class,()->s.complete(m,job.summaryId(),"{\"summary\":\"first\",\"summary\":\"second\"}","p","m"));assertEquals("SUMMARY_OUTPUT_INVALID",failure.getMessage());assertEquals("FAILED",s.get(viewer,agent,c.conversationId(),job.summaryId()).state());assertFalse(s.prepare(m,viewer,agent,c.conversationId(),request,0,2,2048,128,null).dispatch());
        }
    }
}
