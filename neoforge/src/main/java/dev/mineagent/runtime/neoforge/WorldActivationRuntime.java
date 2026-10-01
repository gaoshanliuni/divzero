package dev.mineagent.runtime.neoforge;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.core.permission.WorldActivation;
import dev.mineagent.runtime.core.permission.WorldActivation.State;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** A connection-bound choice, persisted before refreshing the live client and server. */
public final class WorldActivationRuntime {
    private static final Map<ServerPlayer, UUID> CHALLENGES = new WeakHashMap<>();
    public static State state(ServerPlayer player) {
        return WorldActivation.state(MineAgentRuntimeServices.config(player.level().getServer()).snapshot().values(), MineAgentRuntimeServices.worldId(player.level().getServer()), player.getUUID());
    }
    public static void login(ServerPlayer player) {
        CHALLENGES.put(player, UUID.randomUUID());
        refreshPermissions(player);
    }
    public static void refreshPermissions(ServerPlayer player) {
        MineAgentRuntimeServices.permissions(player.level().getServer()).setPlayerEnabled(player.getUUID(), state(player) == State.ENABLED);
    }
    public static void snapshot(ServerPlayer player, Map<String, String> values) {
        values.keySet().removeIf(k -> k.startsWith("runtime.activation."));
        values.put("runtime.activation.state", state(player).name());
        values.put("runtime.activation.challenge", CHALLENGES.computeIfAbsent(player, p -> UUID.randomUUID()).toString());
    }
    public static int decide(CommandSourceStack source, boolean enabled, String challenge) {
        try {
            var player = source.getPlayerOrException();
            var server = source.getServer();
            if (!server.isSameThread() || player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer
                    || server.getPlayerList().getPlayer(player.getUUID()) != player) return 0;
            if (!WorldIdentityRuntime.notifyIfPending(player)) return 0;
            if (challenge != null && !challenge.equals(CHALLENGES.get(player) == null ? "" : CHALLENGES.get(player).toString())) {
                source.sendFailure(Component.translatable("mineagent.activation.stale"));
                return 0;
            }
            var config = MineAgentRuntimeServices.config(server);
            boolean changed = state(player) != (enabled ? State.ENABLED : State.DISABLED);
            var patch = new LinkedHashMap<String, String>();
            patch.put(WorldActivation.key(MineAgentRuntimeServices.worldId(server), player.getUUID()), enabled ? "ENABLED" : "DISABLED");
            if (enabled) patch.put("runtime.initialized", "true");
            // This fixed internal patch only changes the caller's participation, never permission.player.*.
            if(changed){var result = config.apply(new ConfigPatch(config.snapshot().revision(), patch), true);
                if (!result.accepted()) throw new IllegalStateException(result.errorCode());}
            CHALLENGES.put(player, UUID.randomUUID());
            refreshPermissions(player);
            if (!enabled) {
                dev.mineagent.runtime.neoforge.ui.ServerConversations.disconnectIfPresent(server, player);
                dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.disconnect(player);
                dev.mineagent.runtime.neoforge.task.PlayerBodyAgent.stopIfPresent(player);
            }
            if(changed)dev.mineagent.runtime.neoforge.ui.ServerNativeInterfaceRestore.invalidate(player);
            server.getCommands().sendCommands(player);
            MineAgentNetwork.sendPanelSnapshot(player);
            if (enabled) {
                MineAgentNetwork.sendMediaStateTo(player);
                MineAgentNetwork.sendPackageStateTo(player);
            }
            source.sendSuccess(() -> Component.translatable(enabled ? "mineagent.activation.enabled" : "mineagent.activation.disabled"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.translatable("mineagent.activation.failed", Objects.toString(failure.getMessage(), "UNKNOWN")));
            return 0;
        }
    }
    private WorldActivationRuntime() {}
}
