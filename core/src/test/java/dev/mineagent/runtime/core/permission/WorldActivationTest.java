package dev.mineagent.runtime.core.permission;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.config.ServerConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mineagent.runtime.core.permission.WorldActivation.State.*;

class WorldActivationTest {
    @TempDir Path temporary;
    private final UUID world=UUID.randomUUID();
    @Test void decisionsPersistPerWorldAndPlayerWithoutChangingGrants() throws Exception {
        UUID player=UUID.randomUUID(),other=UUID.randomUUID();Path db=temporary.resolve("world.db");
        try(var config=ServerConfigService.open(db)){
            assertEquals(UNDECIDED,WorldActivation.state(config.snapshot().values(),world,player));
            assertTrue(config.apply(new ConfigPatch(config.snapshot().revision(),Map.of(WorldActivation.key(world,player),"DISABLED")),true).accepted());
        }
        try(var config=ServerConfigService.open(db)){
            assertEquals(DISABLED,WorldActivation.state(config.snapshot().values(),world,player));
            assertEquals(UNDECIDED,WorldActivation.state(config.snapshot().values(),world,other));
            assertTrue(config.apply(new ConfigPatch(config.snapshot().revision(),Map.of(WorldActivation.key(world,player),"ENABLED","runtime.initialized","true")),true).accepted());
            assertFalse(config.snapshot().values().containsKey("permission.player."+player));
            assertEquals(UNDECIDED,WorldActivation.state(config.snapshot().values(),UUID.randomUUID(),player));
            assertEquals(UNDECIDED,WorldActivation.state(config.snapshot().values(),world,other));
        }
        try(var config=ServerConfigService.open(db);var different=ServerConfigService.open(temporary.resolve("other-world.db"))){
            assertEquals(ENABLED,WorldActivation.state(config.snapshot().values(),world,player));
            assertEquals(UNDECIDED,WorldActivation.state(different.snapshot().values(),world,player));
        }
    }
    @Test void disableBlocksOperatorsAndOrdinaryPlayersAndInvalidatesLeases() {
        var permissions=new PermissionService();UUID player=UUID.randomUUID(),other=UUID.randomUUID();
        permissions.setTrustedActions(player,Set.of(PermissionAction.MANAGE_PROVIDERS));
        long before=permissions.actionRevision(player,PermissionAction.START_TASK);
        permissions.setPlayerEnabled(player,false);
        assertFalse(permissions.allowed(player,true,PermissionAction.CREATE_AGENT));
        assertFalse(permissions.allowed(player,false,PermissionAction.CHAT));
        assertTrue(permissions.allowed(other,false,PermissionAction.CHAT));
        assertTrue(permissions.actionRevision(player,PermissionAction.START_TASK)>before);
        permissions.setPlayerEnabled(player,true);
        assertTrue(permissions.allowed(player,false,PermissionAction.CHAT));
        assertTrue(permissions.allowed(player,false,PermissionAction.MANAGE_PROVIDERS));
        assertFalse(permissions.allowed(player,false,PermissionAction.CREATE_AGENT));
        assertEquals(Set.of(PermissionAction.MANAGE_PROVIDERS),permissions.trustedActions(player));
    }
    @Test void legacyGlobalFlagCannotEnableAnotherWorld() {
        UUID player=UUID.randomUUID(),other=UUID.randomUUID();var values=new HashMap<String,String>();
        values.put("runtime.initialized","true");values.put("permission.player."+player,"CHAT");
        assertEquals(UNDECIDED,WorldActivation.state(values,world,player));assertEquals(UNDECIDED,WorldActivation.state(values,world,other));
        values.put(WorldActivation.key(world,player),"DISABLED");assertEquals(DISABLED,WorldActivation.state(values,world,player));
        var config=new ServerConfigService();assertFalse(config.apply(new ConfigPatch(0,Map.of(WorldActivation.key(world,player),"INVALID")),true).accepted());
    }
}
