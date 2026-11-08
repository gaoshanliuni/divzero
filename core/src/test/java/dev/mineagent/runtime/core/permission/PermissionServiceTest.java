package dev.mineagent.runtime.core.permission;

import dev.mineagent.runtime.api.permission.PermissionAction;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceTest {
    @Test void persistedGenerationsTrackLegacyCodeGrantImplications(){
        var player=UUID.randomUUID();String key="permission.player."+player;
        var first=dev.mineagent.runtime.core.config.PermissionGenerations.advance(java.util.Map.of(),java.util.Map.of(),java.util.Map.of(key,"RUN_CODE"));
        String world=dev.mineagent.runtime.core.config.PermissionGenerations.key(player,PermissionAction.EDIT_WORLD);
        String entity=dev.mineagent.runtime.core.config.PermissionGenerations.key(player,PermissionAction.EDIT_ENTITIES);
        assertEquals(1L,first.get(world));assertEquals(1L,first.get(entity));
        var revoked=dev.mineagent.runtime.core.config.PermissionGenerations.advance(first,java.util.Map.of(key,"RUN_CODE"),java.util.Map.of(key,"EDIT_WORLD"));
        assertEquals(1L,revoked.get(world));assertEquals(2L,revoked.get(entity));
    }
    @org.junit.jupiter.api.Test void scopedWorldGrantDoesNotGrantCodeOrEntityEdits() {
        var service=new PermissionService();var player=java.util.UUID.randomUUID();
        service.setTrustedActions(player,java.util.Set.of(dev.mineagent.runtime.api.permission.PermissionAction.EDIT_WORLD));
        org.junit.jupiter.api.Assertions.assertTrue(service.allowed(player,false,dev.mineagent.runtime.api.permission.PermissionAction.EDIT_WORLD));
        org.junit.jupiter.api.Assertions.assertFalse(service.allowed(player,false,dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE));
        org.junit.jupiter.api.Assertions.assertFalse(service.allowed(player,false,dev.mineagent.runtime.api.permission.PermissionAction.EDIT_ENTITIES));
        long prior=service.actionRevision(player,dev.mineagent.runtime.api.permission.PermissionAction.EDIT_WORLD);
        service.setTrustedActions(player,java.util.Set.of());
        org.junit.jupiter.api.Assertions.assertTrue(service.actionRevision(player,dev.mineagent.runtime.api.permission.PermissionAction.EDIT_WORLD)>prior);
        service.setTrustedActions(player,java.util.Set.of(dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE));
        org.junit.jupiter.api.Assertions.assertTrue(service.allowed(player,false,dev.mineagent.runtime.api.permission.PermissionAction.EDIT_ENTITIES));
        service.setPlayerEnabled(player,false);
        org.junit.jupiter.api.Assertions.assertFalse(service.allowed(player,true,dev.mineagent.runtime.api.permission.PermissionAction.EDIT_WORLD));
    }

    @Test void perActionAuthorityRevisionCannotBeRevivedByRegrant(){
        var service=new PermissionService();var player=UUID.randomUUID();var action=PermissionAction.DISCOVER_OBJECTS;
        assertEquals(0,service.actionRevision(player,action));service.setTrustedActions(player,Set.of(action));long first=service.actionRevision(player,action);
        service.setTrustedActions(player,Set.of(action,PermissionAction.CHAT));assertEquals(first,service.actionRevision(player,action));
        service.setTrustedActions(player,Set.of());assertTrue(service.actionRevision(player,action)>first);long revoked=service.actionRevision(player,action);
        service.setTrustedActions(player,Set.of(action));assertTrue(service.actionRevision(player,action)>revoked);
    }
    private final UUID owner = UUID.randomUUID();
    private final UUID collaborator = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    @Test
    void regularPlayersCanTalkAndStartOrdinaryTasksOnly() {
        var service = new PermissionService();

        assertTrue(service.allowed(stranger, false, PermissionAction.CHAT));
        assertTrue(service.allowed(stranger, false, PermissionAction.START_TASK));
        assertFalse(service.allowed(stranger, false, PermissionAction.CREATE_AGENT));
        assertFalse(service.allowed(stranger, false, PermissionAction.RUN_CODE));
        assertFalse(service.allowed(stranger, false, PermissionAction.CONTROL_PUBLIC_MEDIA));
        assertFalse(service.allowed(stranger, false, PermissionAction.MANAGE_SCOREBOARD));
    }

    @Test
    void operatorsCanPerformEveryAction() {
        var service = new PermissionService();

        for (PermissionAction action : PermissionAction.values()) {
            assertTrue(service.allowed(stranger, true, action), action.name());
        }
    }

    @Test
    void trustedPlayersReceiveConfiguredElevatedActions() {
        var service = new PermissionService();
        service.setTrustedActions(stranger, Set.of(PermissionAction.CREATE_AGENT, PermissionAction.RUN_CODE));

        assertTrue(service.allowed(stranger, false, PermissionAction.CREATE_AGENT));
        assertTrue(service.allowed(stranger, false, PermissionAction.RUN_CODE));
        assertFalse(service.allowed(stranger, false, PermissionAction.MANAGE_PROVIDERS));
    }

    @Test
    void onlyOwnerCollaboratorOrOperatorCanMutateOwnedAgent() {
        var service = new PermissionService();
        service.registerOwnership(agent, owner);
        service.setCollaborators(agent, owner, Set.of(collaborator));

        assertTrue(service.canMutateAgent(agent, owner, false));
        assertTrue(service.canMutateAgent(agent, collaborator, false));
        assertFalse(service.canMutateAgent(agent, stranger, false));
        assertTrue(service.canMutateAgent(agent, stranger, true));
    }

    @Test
    void nonOwnerCannotChangeCollaborators() {
        var service = new PermissionService();
        service.registerOwnership(agent, owner);

        assertThrows(SecurityException.class,
                () -> service.setCollaborators(agent, stranger, Set.of(collaborator)));
        assertTrue(service.collaborators(agent).isEmpty());
    }
}
