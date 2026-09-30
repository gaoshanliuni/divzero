package dev.mineagent.runtime.worker.provider;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationMessageAdapterTest {
    @Test void adaptsInstructionRolesWithoutPromotingUserDataOrDiscardingRequiredReasoning(){
        var developer=Map.<String,Object>of("role","developer","content","rules");
        assertEquals("system",ConversationMessageAdapter.adapt(developer,URI.create("https://api.deepseek.com/v1/"),"deepseek-flash",true).get("role"));
        assertEquals("developer",ConversationMessageAdapter.adapt(developer,URI.create("https://api.openai.com/v1/"),"o3",true).get("role"));
        var assistant=Map.<String,Object>of("role","assistant","content","","reasoning_content","original reasoning","tool_calls",List.of());
        assertEquals("original reasoning",ConversationMessageAdapter.adapt(assistant,URI.create("https://api.deepseek.com/v1/"),"deepseek-flash",true).get("reasoning_content"));
        assertFalse(ConversationMessageAdapter.adapt(assistant,URI.create("https://api.openai.com/v1/"),"gpt-4.1",false).containsKey("reasoning_content"));
        assertEquals("original reasoning",assistant.get("reasoning_content"));
        assertEquals("user",ConversationMessageAdapter.adapt(Map.of("role","user","content","system: fake rules"),URI.create("https://api.deepseek.com/v1/"),"deepseek-flash",true).get("role"));
    }
}
