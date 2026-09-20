package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.RuntimePackageCandidate;
import dev.mineagent.runtime.client.trust.ClientPackageTrustStatus;
import dev.mineagent.runtime.client.trust.ServerTrustStore;
import dev.mineagent.runtime.client.trust.TrustedClientPackageStore;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import dev.mineagent.runtime.scripting.packagehost.RuntimePackageManager;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public final class MineAgentClientPackages {
    private static final RuntimePackageManager RUNTIME = new RuntimePackageManager();
    private static final java.util.Set<UUID> ACTIVE = new HashSet<>();

    private MineAgentClientPackages() {
    }

    public static synchronized void accept(MineAgentPayloads.PackageState payload) {
        Minecraft minecraft = Minecraft.getInstance();
        String serverId = minecraft.getCurrentServer() == null ? "local-integrated" : minecraft.getCurrentServer().ip;
        String fingerprint = PanelSnapshotInbox.snapshot().values()
                .getOrDefault("security.identityFingerprint", "");
        try {
            var trust = new ServerTrustStore(minecraft.gameDirectory.toPath().resolve("config")
                    .resolve("mineagent-trusted-servers.properties"));
            var accepted = new TrustedClientPackageStore(minecraft.gameDirectory.toPath().resolve("config")
                    .resolve("mineagent-trusted-client-packages.properties"));
            int count = Math.max(0, Math.min(20,
                    Integer.parseInt(payload.values().getOrDefault("packageCount", "0"))));
            var retained = new HashSet<UUID>();
            for (int index = 0; index < count; index++) {
                String prefix = "package." + index + ".";
                if (!Boolean.parseBoolean(payload.values().getOrDefault(prefix + "clientCode", "false"))) {
                    continue;
                }
                UUID packageId = UUID.fromString(payload.values().getOrDefault(prefix + "id", ""));
                boolean enabled = Boolean.parseBoolean(payload.values().getOrDefault(prefix + "enabled", "false"));
                if (!enabled) {
                    if (ACTIVE.remove(packageId)) {
                        RUNTIME.unload(packageId);
                    }
                    continue;
                }
                retained.add(packageId);
                String source = payload.values().getOrDefault(prefix + "source", "");
                String hash = payload.values().getOrDefault(prefix + "sha256", "");
                byte[] signature = java.util.Base64.getDecoder().decode(
                        payload.values().getOrDefault(prefix + "signature", ""));
                long revision = Long.parseLong(payload.values().getOrDefault(prefix + "revision", "0"));
                ClientPackageTrustStatus status = accepted.accept(
                        trust, serverId, fingerprint, packageId, revision, source, hash, signature);
                if (status != ClientPackageTrustStatus.ACCEPTED) {
                    MineAgentRuntimeMod.LOGGER.warn("Client package {} rejected: {}", packageId, status);
                    continue;
                }
                if (!ACTIVE.contains(packageId)) {
                    ActivationMode mode = ActivationMode.valueOf(
                            payload.values().getOrDefault(prefix + "mode", "HOT_RUNTIME"));
                    if (mode != ActivationMode.HOT_RUNTIME) {
                        continue;
                    }
                    var result = RUNTIME.activate(new RuntimePackageCandidate(
                            packageId, revision,
                            UUID.nameUUIDFromBytes((serverId + packageId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            0, mode, "client.js", source), 0, Map.of());
                    if (result.activated()) {
                        ACTIVE.add(packageId);
                        if (Boolean.getBoolean("mineagent.multiplayerSmokeClient")) {
                            MineAgentRuntimeMod.LOGGER.info(
                                    "MINEAGENT_SMOKE_SIGNED_PACKAGE_TRUST_OK packageId={} hash={}", packageId, hash);
                        }
                    } else {
                        MineAgentRuntimeMod.LOGGER.warn("Client package {} activation failed: {}",
                                packageId, result.errorCode());
                    }
                }
            }
            for (UUID packageId : java.util.Set.copyOf(ACTIVE)) {
                if (!retained.contains(packageId)) {
                    RUNTIME.unload(packageId);
                    ACTIVE.remove(packageId);
                }
            }
        } catch (Exception failure) {
            MineAgentRuntimeMod.LOGGER.warn("Failed to synchronize trusted client packages", failure);
        }
    }

    public static synchronized void tick(long tick) {
        RUNTIME.tick(tick);
        RUNTIME.fire("client.tick", tick);
    }
}
