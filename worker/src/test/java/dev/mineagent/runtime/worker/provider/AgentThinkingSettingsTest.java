package dev.mineagent.runtime.worker.provider;
import org.junit.jupiter.api.Test;import com.fasterxml.jackson.databind.ObjectMapper;import java.net.URI;import static org.junit.jupiter.api.Assertions.*;
class AgentThinkingSettingsTest {
 @Test void thinkingEffortAndDisplayAreIndependent(){for(String level:java.util.List.of("off","low","medium","high","max")){var body=new ObjectMapper().createObjectNode();OpenAiCompatibleProvider.configureConversationThinking(URI.create("https://api.deepseek.com/v1/"),"deepseek-flash",body,level);assertEquals(level.equals("off")?"disabled":"enabled",body.path("thinking").path("type").asText());if(level.equals("off"))assertFalse(body.has("reasoning_effort"));else assertEquals(level,body.path("reasoning_effort").asText());assertFalse(body.has("max_tokens"));}}
 @Test void notInjectedIntoUnrelatedProviders(){var body=new ObjectMapper().createObjectNode();OpenAiCompatibleProvider.configureConversationThinking(URI.create("http://localhost:11434/v1/"),"qwen3:8b",body,"max");assertTrue(body.isEmpty());assertTrue(ProviderModelCatalog.keyOptional("http://localhost:11434/v1/"));assertFalse(ProviderModelCatalog.keyOptional("https://api.deepseek.com/v1/"));}
}
