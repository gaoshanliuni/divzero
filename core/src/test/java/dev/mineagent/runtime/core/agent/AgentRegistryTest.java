package dev.mineagent.runtime.core.agent;

import dev.mineagent.runtime.api.agent.AgentMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {
    @Test void namesFitNativeProfileWireAndCommandGrammar(){
        assertEquals("中文伙伴",AgentProfileNames.require("中文伙伴"));
        assertEquals("abcdefghijklmnop",AgentProfileNames.require("abcdefghijklmnop"));
        for(String name:java.util.List.of("a".repeat(17),"两个 名字","@a","a\"b","a\nb","a\u202eb","a\u00a7b"))assertThrows(IllegalArgumentException.class,()->AgentProfileNames.require(name),name);
        var registry=new AgentRegistry(4);var id=UUID.randomUUID();var legacy=new dev.mineagent.runtime.api.agent.AgentDefinition(id,"old name with spaces","MA_legacy",UUID.randomUUID(),AgentMode.SURVIVAL,java.util.Set.of());
        registry.restore(legacy);assertEquals(legacy,registry.get(id).orElseThrow());
    }
    @Test
    void createsAtMostFourNamedAgentsAndTracksOwnership() {
        var registry = new AgentRegistry(4);
        UUID owner = UUID.randomUUID();

        var steve = registry.create("Steve", owner, AgentMode.CREATOR);
        registry.create("Alex", owner, AgentMode.SURVIVAL);
        registry.create("Robin", owner, AgentMode.CREATOR);
        registry.create("Builder", owner, AgentMode.CREATOR);

        assertEquals(owner, steve.ownerPlayerId());
        assertEquals("Steve",steve.profileName());
        assertEquals(4, registry.all().size());
        assertThrows(AgentLimitException.class,
                () -> registry.create("Fifth", owner, AgentMode.CREATOR));
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() {
        var registry = new AgentRegistry(4);
        UUID owner = UUID.randomUUID();
        registry.create("Steve", owner, AgentMode.CREATOR);

        assertThrows(IllegalArgumentException.class,
                () -> registry.create("steve", owner, AgentMode.SURVIVAL));
    }

    @Test
    void ownerCanGrantAndRevokeCollaborator() {
        var registry = new AgentRegistry(4);
        UUID owner = UUID.randomUUID();
        UUID collaborator = UUID.randomUUID();
        var agent = registry.create("Steve", owner, AgentMode.CREATOR);

        assertTrue(registry.setCollaborator(agent.agentId(), owner, collaborator, true));
        assertTrue(registry.get(agent.agentId()).orElseThrow().collaboratorPlayerIds().contains(collaborator));
        assertTrue(registry.setCollaborator(agent.agentId(), owner, collaborator, false));
        assertFalse(registry.get(agent.agentId()).orElseThrow().collaboratorPlayerIds().contains(collaborator));
    }

    @Test
    void ownerCanRenameChangeModeAndDeleteAgent() {
        var registry = new AgentRegistry(4);
        UUID owner = UUID.randomUUID();
        var agent = registry.create("Steve", owner, AgentMode.CREATOR);

        assertTrue(registry.rename(agent.agentId(), owner, false, "建筑师"));
        assertTrue(registry.setMode(agent.agentId(), owner, false, AgentMode.SURVIVAL));
        assertEquals("建筑师", registry.get(agent.agentId()).orElseThrow().displayName());
        assertEquals("建筑师", registry.get(agent.agentId()).orElseThrow().profileName());
        assertEquals(AgentMode.SURVIVAL, registry.get(agent.agentId()).orElseThrow().mode());
        assertTrue(registry.remove(agent.agentId(), owner, false));
        assertTrue(registry.get(agent.agentId()).isEmpty());
    }

    @Test
    void strangerCannotMutateButOperatorCanDeleteAgent() {
        var registry = new AgentRegistry(4);
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        var agent = registry.create("Steve", owner, AgentMode.CREATOR);

        assertFalse(registry.rename(agent.agentId(), stranger, false, "抢占"));
        assertFalse(registry.setMode(agent.agentId(), stranger, false, AgentMode.SURVIVAL));
        assertFalse(registry.remove(agent.agentId(), stranger, false));
        assertTrue(registry.remove(agent.agentId(), stranger, true));
    }

    @Test
    void restoresPersistedDefinitionWithoutChangingIdentity() {
        var registry = new AgentRegistry(4);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var definition = new dev.mineagent.runtime.api.agent.AgentDefinition(
                id, "恢复者", "MA_restore", owner, AgentMode.SURVIVAL, java.util.Set.of());

        registry.restore(definition);

        assertEquals(definition.withDisplayName("恢复者"), registry.get(id).orElseThrow());
        assertEquals("恢复者",registry.get(id).orElseThrow().profileName());
        assertThrows(IllegalArgumentException.class, () -> registry.restore(definition));
    }
}
