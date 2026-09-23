package dev.mineagent.runtime.core.config;
import org.junit.jupiter.api.Test;import dev.mineagent.runtime.api.config.ConfigPatch;import java.util.Map;import static org.junit.jupiter.api.Assertions.*;
class ProviderDefaultsTest {
 @Test void switchingProvidersSelectsDefaultsButPreservesCustomModel(){var c=new ServerConfigService();assertEquals("gpt-4.1-mini",c.snapshot().values().get("provider.openai.model"));assertTrue(c.apply(new ConfigPatch(0,Map.of("provider.openai.baseUrl","https://api.deepseek.com/v1/")),true).accepted());assertEquals("deepseek-flash",c.snapshot().values().get("provider.openai.model"));assertTrue(c.apply(new ConfigPatch(1,Map.of("provider.openai.baseUrl","https://open.bigmodel.cn/api/paas/v4/")),true).accepted());assertEquals("glm-4.6",c.snapshot().values().get("provider.openai.model"));assertTrue(c.apply(new ConfigPatch(2,Map.of("provider.openai.model","my-model")),true).accepted());assertTrue(c.apply(new ConfigPatch(3,Map.of("provider.openai.baseUrl","https://api.deepseek.com/v1/")),true).accepted());assertEquals("my-model",c.snapshot().values().get("provider.openai.model"));assertEquals(Integer.MAX_VALUE,new ServerConfigService().resourceLimits().maxAgents());}
 @Test void legacyProviderWithoutModelUsesItsOwnDefault(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)throws Exception{
  var file=root.resolve("settings.db");try(var db=new dev.mineagent.runtime.core.persistence.SqliteConfigRepository(file)){assertTrue(db.save(0,new dev.mineagent.runtime.core.persistence.StoredConfig(1,Map.of("provider.openai.baseUrl","https://api.deepseek.com/v1/"),Map.of())));}
  try(var c=ServerConfigService.open(file)){assertEquals("deepseek-flash",c.snapshot().values().get("provider.openai.model"));}
 }
}
