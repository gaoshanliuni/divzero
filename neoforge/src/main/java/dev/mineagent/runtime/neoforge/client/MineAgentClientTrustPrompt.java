package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.api.config.PanelSection;
import dev.mineagent.runtime.client.trust.ServerTrustStore;
import dev.mineagent.runtime.client.trust.TrustStatus;
import dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;

public final class MineAgentClientTrustPrompt {
    private static java.util.Map<String, Object> pendingWebNotice;
    private MineAgentClientTrustPrompt() {
    }

    public static void onSnapshot(MineAgentPayloads.PanelSnapshot payload) {
        if (!PanelSnapshotInbox.signatureValid()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String fingerprint = payload.values().getOrDefault("security.identityFingerprint", "");
        String serverId = minecraft.getCurrentServer() == null ? "local-integrated" : minecraft.getCurrentServer().ip;
        try {
            var store = new ServerTrustStore(
                    minecraft.gameDirectory.toPath().resolve("config").resolve("mineagent-trusted-servers.properties"));
            TrustStatus status = store.status(serverId, fingerprint);
            pendingWebNotice = java.util.Map.of("trust", status.name(), "fingerprint", fingerprint,
                    "initialized", Boolean.parseBoolean(payload.values().getOrDefault("runtime.initialized", "false")));
            if (dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.browser() != null) {
                // Do not steal focus from a player's managed form. This notice grants no trust or permissions.
                flushWebNotice();
                return;
            }
            if (Boolean.getBoolean("mineagent.multiplayerSmokeClient")) {
                if (status == TrustStatus.TRUSTED) {
                    dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                            "MINEAGENT_SMOKE_TRUST_RECONNECT_OK fingerprint={}", fingerprint);
                } else {
                    store.confirm(serverId, fingerprint,
                            java.util.Base64.getDecoder().decode(
                                    payload.values().getOrDefault("security.identityPublicKey", "")));
                    dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                            "MINEAGENT_SMOKE_TRUST_ESTABLISHED fingerprint={}", fingerprint);
                }
                return;
            }
            if (status != TrustStatus.TRUSTED && !(minecraft.screen instanceof ControlCenterScreen)) {
                minecraft.setScreen(ControlCenterScreen.create(minecraft.screen, PanelSection.PERMISSIONS));
            } else if (status == TrustStatus.TRUSTED
                    && !Boolean.parseBoolean(payload.values().getOrDefault("runtime.initialized", "false"))
                    && !(minecraft.screen instanceof ControlCenterScreen)) {
                minecraft.setScreen(ControlCenterScreen.create(minecraft.screen, PanelSection.PROVIDERS));
            }
        } catch (Exception ignored) {
            if (!(minecraft.screen instanceof ControlCenterScreen)) {
                minecraft.setScreen(ControlCenterScreen.create(minecraft.screen, PanelSection.PERMISSIONS));
            }
        }
    }
    public static void refreshWebNotice() {
        // Reopening a document must not reuse the previously consumed notice.
        // Read the current signed snapshot/local trust without opening a native
        // screen, confirming trust or changing any permission.
        var minecraft=Minecraft.getInstance();
        try {
            pendingWebNotice=dev.mineagent.runtime.client.trust.WebSetupNotice.read(
                    minecraft.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"),
                    minecraft.getCurrentServer()==null?"local-integrated":minecraft.getCurrentServer().ip,
                    PanelSnapshotInbox.snapshot().values(),PanelSnapshotInbox.signatureValid());
        } catch(Exception unavailable) {
            pendingWebNotice=java.util.Map.of("trust","UNKNOWN","fingerprint","","initialized",false);
        }
        flushWebNotice();
    }
    public static void flushWebNotice() {
        if (pendingWebNotice != null && dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.ready()) {
            dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.emit("setupNotice", pendingWebNotice);
            pendingWebNotice = null;
        }
    }
    public static void clearWebNotice() { pendingWebNotice = null; }
}
