package dev.mineagent.runtime.core.conversation;
import dev.mineagent.runtime.api.agent.*;
import dev.mineagent.runtime.core.agent.AgentPersonaService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ConversationStructuredContextTest {
    @TempDir Path directory;
    @Test void separatesApplicationRulesHistoryPlayerPersonaAndLatestInput()throws Exception{
        UUID owner=UUID.randomUUID();var agent=new AgentDefinition(UUID.randomUUID(),"A","profile",owner,AgentMode.SURVIVAL,Set.of());
        var persona=new AgentPersonaService.Persona(agent.agentId(),1,"PERSONA_DATA",owner,1);
        try(var store=ConversationStore.open(directory.resolve("records.db"),UUID.randomUUID(),Clock.systemUTC())){
            var c=store.create(owner,agent.agentId(),UUID.randomUUID(),"dialogue");var prior=store.begin(owner,agent.agentId(),c.conversationId(),UUID.randomUUID(),1,"OLD_USER",1);store.finish(prior.operationId(),"COMPLETE","OLD_ASSISTANT","");
            var current=store.begin(owner,agent.agentId(),c.conversationId(),UUID.randomUUID(),1,"LATEST_PLAYER_TEXT",1);
            var plan=ConversationContext.build(store,owner,agent,c.conversationId(),current,persona,"LATEST_PLAYER_TEXT",16000,null,"MEMORY_DATA");
            assertEquals("system",plan.messages().getFirst().get("role"));assertFalse(plan.messages().getFirst().get("content").toString().contains("PERSONA_DATA"));
            assertEquals(Map.of("role","user","content","LATEST_PLAYER_TEXT"),plan.messages().getLast());
            assertTrue(plan.messages().stream().anyMatch(m->m.get("role").equals("assistant")&&m.get("content").toString().contains("OLD_ASSISTANT")));
            assertTrue(plan.messages().stream().anyMatch(m->m.get("role").equals("user")&&m.get("content").toString().contains("data_context")&&m.get("content").toString().contains("MEMORY_DATA")));
        }
    }
}
