package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolScopeTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void allAgentsIntentLoadsManagementAndMovementWithoutUnrelatedCatalog(){
        var session=new CapabilitySession();session.preload("所有 ai 调整为跟随模式",List.of());
        assertTrue(session.groups().containsAll(List.of("agents","movement")));assertTrue(session.tools().contains("inspect_owned_agents"));
        assertFalse(session.tools().contains("python_execute"));assertFalse(session.tools().contains("plan_building"));
        var follow=session.definitions().stream().filter(d->d.name().equals("follow_entity")).findFirst().orElseThrow();assertTrue(follow.parameters().contains("agent_id"));
    }
    @Test void siblingRoutingIsOptionalTypedAndDoesNotConfuseFollowTarget(){
        var current=UUID.randomUUID();var sibling=UUID.randomUUID();var n=json.createObjectNode().put("target","$owner");
        assertEquals(current,AgentToolScope.target("follow_entity",n,current));n.put("agent_id",sibling.toString());assertEquals(sibling,AgentToolScope.target("follow_entity",n,current));assertEquals("$owner",n.get("target").asText());
        assertFalse(ToolValidation.check("inspect_persona",json.createObjectNode().put("agent_id",sibling.toString())).issues().size()>0);
        assertFalse(ToolValidation.check("inspect_persona",json.createObjectNode().put("agent_id","not-a-uuid")).issues().isEmpty());
        assertThrows(IllegalArgumentException.class,()->AgentToolScope.target("python_execute",n,current));
    }
    @Test void ownerIsNotAnOperatorOrCollaboratorShortcut(){
        var player=UUID.randomUUID();var other=UUID.randomUUID();assertTrue(AgentToolScope.sameOwner(player,player,player));
        assertFalse(AgentToolScope.sameOwner(player,player,other));assertFalse(AgentToolScope.sameOwner(player,other,player));assertFalse(AgentToolScope.sameOwner(other,player,player));
    }
}
