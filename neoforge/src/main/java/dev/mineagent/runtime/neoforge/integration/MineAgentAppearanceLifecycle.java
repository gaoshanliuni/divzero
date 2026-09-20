package dev.mineagent.runtime.neoforge.integration;

import dev.mineagent.runtime.integrations.ysm.AppearanceLifecycleCoordinator;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

public final class MineAgentAppearanceLifecycle {
    private static final java.util.Map<MinecraftServer, java.util.Map<String, Stamp>> APPLIED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private MineAgentAppearanceLifecycle() {
    }

    public static Optional<AppearanceLifecycleCoordinator.Result> reapply(
            MinecraftServer server,
            UUID agentId,
            String reason
    ) {
        var config = MineAgentRuntimeServices.config(server);
        String prefix = "agent." + agentId + ".";
        String currentWorld = MineAgentRuntimeServices.worldId(server).toString();
        var worldValidation = dev.mineagent.runtime.integrations.ysm.AppearanceWorldScope
                .validatePersistedIntent(config, prefix, currentWorld);
        var values = worldValidation.snapshot().values();
        String model = values.getOrDefault(prefix + "model", "");
        if (model.isBlank()) {
            return Optional.empty();
        }
        String storedWorld = values.getOrDefault(prefix + "appearanceWorldId", "");
        if (!worldValidation.usable()) {
            var result = new AppearanceLifecycleCoordinator.Result(
                    false, worldValidation.diagnosticCode(), 0, reason);
            MineAgentRuntimeMod.LOGGER.warn(
                    "MINEAGENT_YSM_LIFECYCLE agent={} reason={} applied=false diagnostic={} storedWorld={} currentWorld={}",
                    agentId, reason, result.diagnosticCode(), storedWorld, currentWorld);
            return Optional.of(result);
        }
        long revision;
        try {
            revision = Long.parseLong(values.getOrDefault(prefix + "appearanceRevision", "0"));
        } catch (NumberFormatException invalid) {
            MineAgentRuntimeMod.LOGGER.error("Invalid persisted appearance revision for agent={}", agentId);
            return Optional.empty();
        }
        var intent = new AppearanceLifecycleCoordinator.Intent(
                agentId,
                model,
                values.getOrDefault(prefix + "texture", ""),
                values.getOrDefault(prefix + "animation", ""),
                revision
        );
        var currentBody = server.getPlayerList().getPlayer(agentId);
        int entityId = currentBody == null ? -1 : currentBody.getId();
        String trackingKey = agentId + ":" + reason;
        var applied = APPLIED.computeIfAbsent(server, ignored -> new java.util.HashMap<>());
        Stamp stamp = new Stamp(entityId, revision);
        if (reason.startsWith("tracking:") && stamp.equals(applied.get(trackingKey))) {
            return Optional.of(new AppearanceLifecycleCoordinator.Result(true, "", revision, reason));
        }
        var result = new AppearanceLifecycleCoordinator(new NeoForgeYsmRuntimeBridge(server)).reapply(intent, reason);
        if (result.applied()) {
            if (!reason.startsWith("tracking:")) {
                applied.keySet().removeIf(key -> key.startsWith(agentId + ":tracking:"));
            }
            applied.put(trackingKey, stamp);
        }
        MineAgentRuntimeMod.LOGGER.info(
                "MINEAGENT_YSM_LIFECYCLE agent={} reason={} revision={} applied={} diagnostic={}",
                agentId, reason, revision, result.applied(), result.diagnosticCode());
        return Optional.of(result);
    }

    private record Stamp(int entityId, long revision) {
    }
}
