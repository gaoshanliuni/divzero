package dev.mineagent.runtime.core.agent;

import dev.mineagent.runtime.api.agent.AgentMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersistentAgentServiceTest {
    @Test void legacyNameMigrationKeepsUuidOwnerAuthorityAndSurvivesRestart()throws Exception{
        Path path=temporaryDirectory.resolve("legacy.db");UUID world=UUID.randomUUID(),id=UUID.randomUUID(),owner=UUID.randomUUID();
        var old=new PersistentAgent(1,new dev.mineagent.runtime.api.agent.AgentDefinition(id,"中文伙伴","MA_legacy",owner,AgentMode.SURVIVAL,java.util.Set.of()),7);
        try(var db=new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(path)){
            assertTrue(db.compareAndSet(world,"agents",id.toString(),0,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(old),0).accepted());
        }
        try(var service=PersistentAgentService.open(path,world,4)){
            var migrated=service.get(id).orElseThrow();assertEquals(id,migrated.definition().agentId());assertEquals(owner,migrated.definition().ownerPlayerId());
            assertEquals(7,migrated.authorityGeneration());assertEquals(2,migrated.revision());assertEquals("中文伙伴",migrated.definition().profileName());
            assertEquals("MA_legacy",service.previousProfileName(id).orElseThrow());
        }
        try(var service=PersistentAgentService.open(path,world,4)){assertEquals(2,service.get(id).orElseThrow().revision());}
    }
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsDefinitionMutationsAndDeletionAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID collaborator = UUID.randomUUID();
        UUID agentId;
        try (var service = PersistentAgentService.open(database, world, 4)) {
            var created = service.create("Steve", owner, AgentMode.CREATOR);
            agentId = created.definition().agentId();
            var renamed = service.rename(agentId, created.revision(), owner, false, "建筑师");
            var shared = service.setCollaborator(
                    agentId, renamed.agent().revision(), owner, collaborator, true);

            assertTrue(renamed.accepted());
            assertTrue(shared.accepted());
        }

        try (var reopened = PersistentAgentService.open(database, world, 4)) {
            var restored = reopened.get(agentId).orElseThrow();
            assertEquals("建筑师", restored.definition().displayName());
            assertTrue(restored.definition().collaboratorPlayerIds().contains(collaborator));
            assertTrue(reopened.delete(agentId, restored.revision(), owner, false).accepted());
        }

        try (var reopened = PersistentAgentService.open(database, world, 4)) {
            assertTrue(reopened.get(agentId).isEmpty());
        }
    }

    @Test
    void staleAndUnauthorizedMutationDoNotChangeDefinition() throws Exception {
        try (var service = PersistentAgentService.open(
                temporaryDirectory.resolve("runtime.db"), UUID.randomUUID(), 4)) {
            UUID owner = UUID.randomUUID();
            var created = service.create("Steve", owner, AgentMode.CREATOR);

            assertEquals("FORBIDDEN", service.rename(created.definition().agentId(), created.revision(),
                    UUID.randomUUID(), false, "抢占").errorCode());
            var renamed = service.rename(created.definition().agentId(), created.revision(), owner, false, "Alex");
            assertEquals("STALE_REVISION", service.setMode(created.definition().agentId(), created.revision(),
                    owner, false, AgentMode.SURVIVAL).errorCode());
            assertEquals("Alex", renamed.agent().definition().displayName());
        }
    }
}
