package dev.mineagent.runtime.core.config;
import dev.mineagent.runtime.api.config.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.permission.PermissionService;
import java.util.*;

/** Persist first. A failed CAS must never grant authority only in memory. */
public final class PermissionConfig {
    private PermissionConfig(){}
    public static ConfigPatchResult apply(ServerConfigService config,PermissionService permissions,long revision,UUID player,Set<PermissionAction> actions,boolean authorized){
        Objects.requireNonNull(player);Objects.requireNonNull(actions);
        synchronized(config){var result=config.apply(new ConfigPatch(revision,Map.of("permission.player."+player,actions.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")))),authorized);
            if(result.accepted())permissions.setTrustedActions(player,actions);return result;}
    }
}
