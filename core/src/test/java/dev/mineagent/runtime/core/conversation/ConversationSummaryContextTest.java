package dev.mineagent.runtime.core.conversation;
import dev.mineagent.runtime.api.agent.*;
import dev.mineagent.runtime.core.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationSummaryContextTest {
    @TempDir Path dir;
    @Test void disabledSummariesDoNotClaimBackgroundWorkIsRunning()throws Exception{
        UUID world=UUID.randomUUID(),viewer=UUID.randomUUID();var agent=new AgentDefinition(UUID.randomUUID(),"A","a",viewer,AgentMode.CREATOR,Set.of());var persona=new AgentPersonaService.Persona(agent.agentId(),1,"",viewer,0);
        try(var messages=ConversationStore.open(dir.resolve("disabled"),world,Clock.systemUTC())){
            var c=messages.create(viewer,agent.agentId(),UUID.randomUUID(),"chat");for(int i=0;i<12;i++){var t=messages.begin(viewer,agent.agentId(),c.conversationId(),UUID.randomUUID(),1,"history "+"石砖".repeat(100),1);messages.finish(t.operationId(),"COMPLETE","好的","");}
            var turn=messages.begin(viewer,agent.agentId(),c.conversationId(),UUID.randomUUID(),1,"现在继续",1);
            var plan=ConversationContext.build(messages,viewer,agent,c.conversationId(),turn,persona,"现在继续",4096,null,"",8192,false,false);assertEquals("SUMMARY_DISABLED",plan.summaryStatus());assertTrue(plan.prompt().contains("后台摘要已关闭"));assertFalse(plan.prompt().contains("正在后台整理"));assertTrue(plan.estimatedTokens()<=4096);
        }
    }
    @Test void composedContextUsesValidPrefixSummaryAndKeepsCurrentPersonaAndMessageWithinBudget()throws Exception{
        UUID world=UUID.randomUUID(),viewer=UUID.randomUUID();var a=new AgentDefinition(UUID.randomUUID(),"A","a",viewer,AgentMode.CREATOR,Set.of());var persona=new AgentPersonaService.Persona(a.agentId(),2,"最新人设",viewer,0);
        try(var messages=ConversationStore.open(dir.resolve("db"),world,Clock.systemUTC());var summaries=ConversationSummaryStore.open(dir.resolve("db"),world,Clock.systemUTC())){
            var c=messages.create(viewer,a.agentId(),UUID.randomUUID(),"chat");for(int i=0;i<15;i++){var t=messages.begin(viewer,a.agentId(),c.conversationId(),UUID.randomUUID(),1,"old "+i+" "+"x".repeat(150),0);messages.finish(t.operationId(),"COMPLETE","reply "+i,"");}
            var turn=messages.begin(viewer,a.agentId(),c.conversationId(),UUID.randomUUID(),1,"CURRENT",2);var initial=ConversationContext.build(messages,viewer,a,c.conversationId(),turn,persona,"CURRENT",2048);assertEquals("SUMMARY_REQUIRED",initial.summaryStatus());
            ConversationSummaryStore.Summary previous=null;int index=0;while(previous==null||previous.endSequence()<=initial.omittedThrough()||previous.endOffset()!=0){var job=summaries.prepare(messages,viewer,a.agentId(),c.conversationId(),turn.operationId(),index++,initial.omittedThrough(),8192,initial.summaryBudget(),previous);previous=summaries.complete(messages,job.summaryId(),"{\"summary\":\"SUMMARY_FACTS\"}","fixture","fixture");}
            var result=ConversationContext.build(messages,viewer,a,c.conversationId(),turn,persona,"CURRENT",2048,previous);assertEquals("READY",result.summaryStatus());assertEquals(previous.summaryId().toString(),result.summaryId());assertTrue(result.prompt().contains("SUMMARY_FACTS"));assertTrue(result.prompt().contains("最新人设"));assertTrue(result.prompt().endsWith("CURRENT"));assertTrue(result.estimatedTokens()<=2048);assertEquals(32,messages.get(viewer,a.agentId(),c.conversationId()).messageCount());
        }
    }
}
