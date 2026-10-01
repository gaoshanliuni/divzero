package dev.mineagent.runtime.core.config;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class AgentMentionDisplayTest {
    @Test void observerProjectionNeverContainsPrivateData(){var source=new HashMap<String,Object>();source.put("agent","agent");source.put("name","AI");source.put("body","public answer");source.put("thinking","private reasoning");source.put("thinkingOffset",420);source.put("conversationId","private history");source.put("token","secret");var published=dev.mineagent.runtime.core.conversation.PublicMentionUpdate.of(source);assertEquals("public answer",published.get("body"));assertEquals("",published.get("thinking"));assertEquals(0,published.get("thinkingOffset"));assertEquals(true,published.get("observer"));assertFalse(published.containsKey("conversationId"));assertFalse(published.containsKey("token"));}
    @TempDir java.nio.file.Path root;
    @Test void newAgentIsPublicOldAgentStaysPrivateAndSettingPersists()throws Exception{
        var file=root.resolve("policy.db");var world=UUID.randomUUID();var agent=UUID.randomUUID();
        try(var config=ServerConfigService.open(file)){
            assertEquals(AgentMentionDisplay.Mode.PRIVATE,AgentMentionDisplay.read(config.snapshot().values(),world,agent).mode());
            var created=AgentMentionDisplay.create(config,world,agent);assertEquals(AgentMentionDisplay.Mode.PUBLIC,created.mode());
            assertTrue(AgentMentionDisplay.publicReply(created,true));assertFalse(AgentMentionDisplay.publicReply(created,false));
            AgentMentionDisplay.save(config,world,agent,1,AgentMentionDisplay.Mode.PRIVATE);assertEquals(AgentMentionDisplay.Mode.PRIVATE,AgentMentionDisplay.create(config,world,agent).mode());
            assertThrows(IllegalStateException.class,()->AgentMentionDisplay.save(config,world,agent,1,AgentMentionDisplay.Mode.PUBLIC));
            assertEquals(AgentMentionDisplay.Mode.PRIVATE,AgentMentionDisplay.read(config.snapshot().values(),UUID.randomUUID(),agent).mode());
        }
        try(var config=ServerConfigService.open(file)){assertEquals(new AgentMentionDisplay.Setting(AgentMentionDisplay.Mode.PRIVATE,2),AgentMentionDisplay.read(config.snapshot().values(),world,agent));}
    }
}
