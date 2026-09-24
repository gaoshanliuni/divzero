package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationStoreTest {
    @Test void nativeBindingSurvivesRenameAndExplicitWebBindingButNotDelete()throws Exception{try(var s=ConversationStore.open(dir.resolve("native-bind.db"),world,clock)){var c=s.nativeConversation(viewer,agent);s.change(viewer,agent,c.conversationId(),UUID.randomUUID(),c.revision(),"rename","工作对话");assertEquals(c.conversationId(),s.nativeConversation(viewer,agent).conversationId());var web=s.create(viewer,agent,UUID.randomUUID(),"网页会话");s.bindNative(viewer,agent,web.conversationId());assertEquals(web.conversationId(),s.nativeConversation(viewer,agent).conversationId());assertThrows(SecurityException.class,()->s.bindNative(UUID.randomUUID(),agent,web.conversationId()));s.change(viewer,agent,web.conversationId(),UUID.randomUUID(),web.revision(),"delete","");assertNotEquals(web.conversationId(),s.nativeConversation(viewer,agent).conversationId());}}

    @Test void deletedNativeConversationIsNeverReusedForTheNextTurn()throws Exception{try(var store=ConversationStore.open(dir.resolve("native-delete.db"),world,clock)){var first=store.nativeConversation(viewer,agent);var turn=store.begin(viewer,agent,first.conversationId(),UUID.randomUUID(),first.revision(),"old-context-marker",0);store.finish(turn.operationId(),"COMPLETE","old reply","");store.change(viewer,agent,first.conversationId(),UUID.randomUUID(),first.revision(),"delete","");var fresh=store.nativeConversation(viewer,agent);assertNotEquals(first.conversationId(),fresh.conversationId());assertEquals(0,fresh.messageCount());assertTrue(store.forward(viewer,agent,fresh.conversationId(),1,20).isEmpty());assertNotEquals(fresh.conversationId(),store.nativeConversation(UUID.randomUUID(),agent).conversationId());}}

    @Test void feedbackReplyIsScopedIdempotentAndDoesNotReserveASecondModelTurn()throws Exception{
        try(var s=ConversationStore.open(dir.resolve("feedback-reply.db"),world,clock)){var c=s.create(viewer,agent,UUID.randomUUID(),"feedback");UUID op=UUID.randomUUID();var message=s.appendFeedbackReply(viewer,agent,c.conversationId(),op,"已记录你的问题");assertEquals("ASSISTANT",message.role());assertEquals("COMPLETE",message.status());assertEquals(message,s.appendFeedbackReply(viewer,agent,c.conversationId(),op,"已记录你的问题"));assertEquals("",s.get(viewer,agent,c.conversationId()).activeOperation());assertThrows(SecurityException.class,()->s.appendFeedbackReply(UUID.randomUUID(),agent,c.conversationId(),op,"已记录你的问题"));assertThrows(IllegalArgumentException.class,()->s.appendFeedbackReply(viewer,agent,c.conversationId(),op,"different"));}
    }
    @Test void newFeedbackDoesNotMutateTheContextOfAnAlreadyGeneratingAssistant()throws Exception{
        try(var s=ConversationStore.open(dir.resolve("feedback-busy.db"),world,clock)){var c=s.create(viewer,agent,UUID.randomUUID(),"feedback");s.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"active conversation request",0);assertThrows(IllegalStateException.class,()->s.appendFeedback(viewer,agent,c.conversationId(),UUID.randomUUID(),"new feedback"));assertEquals(2,s.get(viewer,agent,c.conversationId()).messageCount());}
    }
    @Test void recordedFeedbackIsAnIdempotentOwnedUserMessageWithoutStartingAssistantGeneration()throws Exception{
        UUID id,op=UUID.randomUUID();
        try(var s=ConversationStore.open(dir.resolve("feedback.db"),world,clock)){
            id=s.create(viewer,agent,UUID.randomUUID(),"独立页面反馈").conversationId();var m=s.appendFeedback(viewer,agent,id,op,"{\"question\":\"private\"}");assertEquals("USER",m.role());assertEquals("COMPLETE",m.status());assertEquals(m,s.appendFeedback(viewer,agent,id,op,"{\"question\":\"private\"}"));assertEquals(1,s.get(viewer,agent,id).messageCount());assertEquals("",s.get(viewer,agent,id).activeOperation());
            assertThrows(SecurityException.class,()->s.appendFeedback(UUID.randomUUID(),agent,id,op,"{\"question\":\"private\"}"));assertThrows(IllegalArgumentException.class,()->s.appendFeedback(viewer,agent,id,op,"different"));
        }
        try(var s=ConversationStore.open(dir.resolve("feedback.db"),world,clock)){s.appendFeedback(viewer,agent,id,op,"{\"question\":\"private\"}");assertEquals(1,s.get(viewer,agent,id).messageCount());s.change(viewer,agent,id,UUID.randomUUID(),1,"archive","");assertThrows(IllegalStateException.class,()->s.appendFeedback(viewer,agent,id,UUID.randomUUID(),"new"));}
    }
    @Test void voiceReadsOnlyCompletedOwnedRepliesAndClaimsItsOperationOnce()throws Exception{
        try(var s=ConversationStore.open(dir.resolve("voice.db"),world,clock)){
            var c=s.create(viewer,agent,UUID.randomUUID(),"voice");var t=s.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"question",0);UUID op=UUID.randomUUID();
            assertThrows(IllegalStateException.class,()->s.claimVoice(viewer,agent,c.conversationId(),t.assistantMessageId(),op));
            s.finish(t.operationId(),"COMPLETE","private reply","");var claimed=s.claimVoice(viewer,agent,c.conversationId(),t.assistantMessageId(),op);assertTrue(claimed.dispatch());assertEquals("private reply",claimed.text());
            assertFalse(s.claimVoice(viewer,agent,c.conversationId(),t.assistantMessageId(),op).dispatch());
            assertThrows(SecurityException.class,()->s.claimVoice(UUID.randomUUID(),agent,c.conversationId(),t.assistantMessageId(),op));
        }
    }
    @Test void thinkingPersistsSeparatelyAndIsPrivatePaginatedAndNotVoiceOrOriginalText()throws Exception{
        UUID id,msg,op=UUID.randomUUID();String thought="私密思考😀".repeat(1000);
        try(var s=ConversationStore.open(dir.resolve("thinking.db"),world,clock)){
            id=s.create(viewer,agent,UUID.randomUUID(),"thinking").conversationId();var t=s.begin(viewer,agent,id,op,1,"question",0);msg=t.assistantMessageId();assertEquals(0,s.thinking(viewer,agent,id,msg).textLength());
            s.thinkingDelta(op,thought,true);assertTrue(s.thinking(viewer,agent,id,msg).active());assertEquals(thought.length(),s.thinking(viewer,agent,id,msg).textLength());assertEquals(0,s.message(viewer,agent,id,msg).textLength());assertEquals(1,s.message(viewer,agent,id,msg).revision());
            long rev=s.thinking(viewer,agent,id,msg).revision();assertThrows(SecurityException.class,()->s.thinkingChunk(UUID.randomUUID(),agent,id,msg,rev,0,4096));assertThrows(SecurityException.class,()->s.thinking(viewer,UUID.randomUUID(),id,msg));
            s.thinkingDelta(op,"尾",false);assertThrows(IllegalStateException.class,()->s.thinkingChunk(viewer,agent,id,msg,rev,0,4096));s.finish(op,"COMPLETE","答案","");assertFalse(s.thinkingDelta(op,"late",true));assertFalse(s.thinking(viewer,agent,id,msg).active());assertEquals("答案",s.claimVoice(viewer,agent,id,msg,UUID.randomUUID()).text());
        }
        try(var s=ConversationStore.open(dir.resolve("thinking.db"),world,clock)){
            var meta=s.thinking(viewer,agent,id,msg);StringBuilder all=new StringBuilder();while(all.length()<meta.textLength())all.append(s.thinkingChunk(viewer,agent,id,msg,meta.revision(),all.length(),4096).text());assertEquals(thought+"尾",all.toString());assertEquals("答案",s.chunk(viewer,agent,id,msg,s.message(viewer,agent,id,msg).revision(),0,4096).text());
        }
    }
    @Test void interruptedAndCancelledThinkingRemainsReadableButNeverAcceptsLateDeltas()throws Exception{
        UUID id,msg,op=UUID.randomUUID();
        try(var s=ConversationStore.open(dir.resolve("thinking-stop.db"),world,clock)){id=s.create(viewer,agent,UUID.randomUUID(),"stop").conversationId();msg=s.begin(viewer,agent,id,op,1,"question",0).assistantMessageId();s.thinkingDelta(op,"partial",true);}
        try(var s=ConversationStore.open(dir.resolve("thinking-stop.db"),world,clock)){assertEquals("INTERRUPTED",s.message(viewer,agent,id,msg).status());assertFalse(s.thinking(viewer,agent,id,msg).active());assertFalse(s.thinkingDelta(op,"late",true));var t=s.begin(viewer,agent,id,UUID.randomUUID(),1,"next",0);s.thinkingDelta(t.operationId(),"cancel me",true);s.cancel(viewer,agent,id,UUID.randomUUID(),t.operationId());assertFalse(s.thinking(viewer,agent,id,t.assistantMessageId()).active());assertFalse(s.thinkingDelta(t.operationId(),"late",true));}
    }
    @TempDir Path dir;final UUID world=UUID.randomUUID(),viewer=UUID.randomUUID(),agent=UUID.randomUUID();final Clock clock=Clock.fixed(Instant.ofEpochMilli(1234),ZoneOffset.UTC);
    @Test void moreThanFortyMessagesPersistWithStableOrderAndOriginalTextAcrossPagesAndRestart()throws Exception{
        UUID id;var expected=new ArrayList<String>();
        try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            id=s.create(viewer,agent,UUID.randomUUID(),"城堡").conversationId();
            for(int i=0;i<51;i++){String user="  第 "+i+" 条\n原文  ",reply="第 "+i+" 个回复";var t=s.begin(viewer,agent,id,UUID.randomUUID(),1,user,0);assertTrue(t.dispatch());s.finish(t.operationId(),"COMPLETE",reply,"");expected.add(user);expected.add(reply);}
        }
        try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            var actual=new ArrayList<String>();long before=0;do{var page=s.messages(viewer,agent,id,before,13);actual.addAll(0,page.messages().stream().map(m->{try{return s.chunk(viewer,agent,id,m.messageId(),m.revision(),0,4096).text();}catch(Exception e){throw new RuntimeException(e);}}).toList());before=page.nextBefore();}while(before>0);
            assertEquals(expected,actual);assertEquals(102,s.get(viewer,agent,id).messageCount());
        }
    }
    @Test void sameParticipantsHaveIndependentExplicitConversationsAndScopedSearch()throws Exception{
        try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            var a=s.create(viewer,agent,UUID.randomUUID(),"城堡");var b=s.create(viewer,agent,UUID.randomUUID(),"日常聊天");assertNotEquals(a.conversationId(),b.conversationId());
            var t=s.begin(viewer,agent,a.conversationId(),UUID.randomUUID(),1,"只属于城堡的关键字",0);s.finish(t.operationId(),"COMPLETE","answer","");
            assertEquals(1,s.list(viewer,agent,"ACTIVE","关键字",0,10).conversations().size());assertEquals(0,s.get(viewer,agent,b.conversationId()).messageCount());
            assertThrows(SecurityException.class,()->s.get(UUID.randomUUID(),agent,a.conversationId()));assertThrows(SecurityException.class,()->s.get(viewer,UUID.randomUUID(),a.conversationId()));
            assertTrue(s.list(UUID.randomUUID(),agent,"ACTIVE","关键字",0,10).conversations().isEmpty());
        }
        try(var s=ConversationStore.open(dir.resolve("db"),UUID.randomUUID(),clock)){assertTrue(s.list(viewer,agent,"ACTIVE","",0,10).conversations().isEmpty());}
    }
    @Test void duplicateTurnsDoNotAppendOrDispatchAndRestartInterruptsWithoutReplay()throws Exception{
        UUID id,op=UUID.randomUUID();try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            id=s.create(viewer,agent,UUID.randomUUID(),"聊天").conversationId();var first=s.begin(viewer,agent,id,op,1,"hello",2);assertTrue(first.dispatch());
            assertFalse(s.begin(viewer,agent,id,op,1,"hello",3).dispatch());assertEquals(2,s.get(viewer,agent,id).messageCount());
            assertThrows(IllegalArgumentException.class,()->s.begin(viewer,agent,id,op,1,"changed",2));
            s.delta(op,"partial");
        }
        try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            assertFalse(s.begin(viewer,agent,id,op,1,"hello",99).dispatch());var m=s.messages(viewer,agent,id,0,10).messages().getLast();assertEquals("INTERRUPTED",m.status());assertEquals("partial",s.chunk(viewer,agent,id,m.messageId(),m.revision(),0,4096).text());
            assertFalse(s.finish(op,"COMPLETE","late", ""));
        }
    }
    @Test void metadataCasArchiveDeleteRestoreKeepRawMessagesAndDoNotCancelAcceptedReply()throws Exception{
        try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            UUID create=UUID.randomUUID();var c=s.create(viewer,agent,create,"A");assertEquals(c.conversationId(),s.create(viewer,agent,create,"A").conversationId());
            var t=s.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"original",0);UUID rename=UUID.randomUUID();
            c=s.change(viewer,agent,c.conversationId(),rename,1,"rename","B");assertEquals(2,c.revision());assertEquals(2,s.change(viewer,agent,c.conversationId(),rename,1,"rename","B").revision());
            final UUID id=c.conversationId();assertThrows(IllegalStateException.class,()->s.change(viewer,agent,id,UUID.randomUUID(),1,"archive",""));
            c=s.change(viewer,agent,id,UUID.randomUUID(),2,"archive","");assertTrue(s.finish(t.operationId(),"COMPLETE","late but original conversation", ""));
            c=s.change(viewer,agent,id,UUID.randomUUID(),3,"delete","");assertEquals("DELETED",c.state());assertEquals(2,c.messageCount());
            assertTrue(s.list(viewer,agent,"ACTIVE","",0,10).conversations().isEmpty());
            c=s.change(viewer,agent,id,UUID.randomUUID(),4,"restore","");assertEquals("ACTIVE",c.state());assertEquals(2,c.messageCount());
        }
    }
    @Test void explicitCancellationRejectsLateResponseAndMessageChunksFenceConcurrentStreaming()throws Exception{
        try(var s=ConversationStore.open(dir.resolve("db"),world,clock)){
            var c=s.create(viewer,agent,UUID.randomUUID(),"A");var t=s.begin(viewer,agent,c.conversationId(),UUID.randomUUID(),1,"question",0);s.delta(t.operationId(),"x".repeat(9000));
            var m=s.messages(viewer,agent,c.conversationId(),0,10).messages().getLast();assertEquals(4096,s.chunk(viewer,agent,c.conversationId(),m.messageId(),m.revision(),0,4096).text().length());
            s.delta(t.operationId(),"more");assertThrows(IllegalStateException.class,()->s.chunk(viewer,agent,c.conversationId(),m.messageId(),m.revision(),4096,4096));
            s.cancel(viewer,agent,c.conversationId(),UUID.randomUUID(),t.operationId());assertFalse(s.finish(t.operationId(),"COMPLETE","late",""));assertEquals("CANCELLED",s.messages(viewer,agent,c.conversationId(),0,10).messages().getLast().status());
        }
    }
}
