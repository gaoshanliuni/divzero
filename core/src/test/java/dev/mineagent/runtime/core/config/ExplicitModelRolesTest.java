package dev.mineagent.runtime.core.config;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExplicitModelRolesTest {
    private WorkerEnvelope request(String capability){return AgentModelSettings.context(new WorkerEnvelope(1,UUID.randomUUID(),"model.completeOnce",Map.of("capability",capability,"prompt","test")),UUID.randomUUID(),UUID.randomUUID());}
    @Test void unspecifiedRolesKeepTheDefaultAndConfiguredCodeUsesTheSameApi(){
        var config=new ServerConfigService();config.apply(new ConfigPatch(0,Map.of("provider.openai.baseUrl","https://example.test/v1")),true);
        var input=request("CODING");assertFalse(AgentModelSettings.bind(config,input).payload().containsKey("agentModel"));
        assertTrue(config.apply(new ConfigPatch(config.revision(),Map.of("provider.openai.role.code","explicit-coder")),true).accepted());
        var output=AgentModelSettings.bind(config,input);assertEquals("code",output.payload().get("modelRole"));assertEquals("explicit-coder",((Map<?,?>)output.payload().get("agentModel")).get("model"));
        assertFalse(AgentModelSettings.bind(config,request("SEMANTIC")).payload().containsKey("agentModel"));
    }
    @Test void summaryAndReviewAreExplicitPhasesAndNoModelIsDispatchedByBinding(){
        var config=new ServerConfigService();config.apply(new ConfigPatch(0,Map.of("provider.openai.baseUrl","https://example.test/v1","provider.openai.role.small","small-explicit")),true);
        var marked=ExplicitModelRoles.mark(request("SEMANTIC"),"small");var output=AgentModelSettings.bind(config,marked);
        assertEquals("small-explicit",((Map<?,?>)output.payload().get("agentModel")).get("model"));
        var history=List.of(Map.of("role","assistant","tool_calls",List.of(Map.of("function",Map.of("name","verify_building")))));
        assertEquals("review",ExplicitModelRoles.role(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",Map.of("toolHistory",history))));
    }
    @Test void roleOverrideDoesNotBypassAnAgentsPinnedApiOrigin()throws Exception{
        var config=new ServerConfigService();var world=UUID.randomUUID();var agent=UUID.randomUUID();
        config.apply(new ConfigPatch(0,Map.of("provider.openai.baseUrl","https://first.test/v1")),true);
        AgentModelSettings.save(config,world,agent,0,"CUSTOM","agent-model","https://first.test/v1");
        config.apply(new ConfigPatch(config.revision(),Map.of("provider.openai.baseUrl","https://second.test/v1","provider.openai.role.code","role-code")),true);
        var input=AgentModelSettings.context(new WorkerEnvelope(1,UUID.randomUUID(),"model.completeOnce",Map.of("capability","CODING","prompt","source")),world,agent);
        assertThrows(IllegalArgumentException.class,()->AgentModelSettings.bind(config,input));
    }
}
