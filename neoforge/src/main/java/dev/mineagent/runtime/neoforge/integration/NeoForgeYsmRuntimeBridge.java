package dev.mineagent.runtime.neoforge.integration;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mineagent.runtime.integrations.ysm.YsmRuntimeBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.neoforged.fml.ModList;

import java.util.UUID;

public final class NeoForgeYsmRuntimeBridge implements YsmRuntimeBridge {
    private static final String MOD_ID = "yes_steve_model";
    private static java.nio.file.Path verifiedPath;
    private static long verifiedSize = -1;
    private static long verifiedModified = -1;
    private static boolean verifiedResult;
    private final MinecraftServer server;

    public NeoForgeYsmRuntimeBridge(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean installed() {
        return ModList.get().isLoaded(MOD_ID);
    }

    @Override
    public String version() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("");
    }

    @Override
    public boolean runtimeAvailable() {
        if (!installed() || !checksumVerified()) {
            return false;
        }
        return PinnedYsmReflection.runtimeAvailable(Thread.currentThread().getContextClassLoader());
    }

    public synchronized boolean checksumVerified() {
        if (!installed()) {
            return false;
        }
        try {
            var file = ModList.get().getModFileById(MOD_ID);
            if (file == null) {
                return false;
            }
            var path = file.getFile().getFilePath().toAbsolutePath().normalize();
            long size = java.nio.file.Files.size(path);
            long modified = java.nio.file.Files.getLastModifiedTime(path).toMillis();
            if (path.equals(verifiedPath) && size == verifiedSize && modified == verifiedModified) {
                return verifiedResult;
            }
            verifiedResult = new dev.mineagent.runtime.integrations.ysm.YsmJarVerifier().verify(path);
            verifiedPath = path;
            verifiedSize = size;
            verifiedModified = modified;
            return verifiedResult;
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        }
    }

    public java.util.List<String> availableModels() {
        if (!runtimeAvailable()) {
            return java.util.List.of();
        }
        return PinnedYsmReflection.availableModels(Thread.currentThread().getContextClassLoader());
    }

    /** Returns the native selection currently observed for transaction readback. */
    public java.util.Optional<dev.mineagent.runtime.integrations.ysm.AppearanceCommitPlan.Selection>
    currentSelection(UUID playerId) {
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null || !runtimeAvailable()) {
            return java.util.Optional.empty();
        }
        return PinnedYsmReflection.readAppliedState(
                        player, Thread.currentThread().getContextClassLoader())
                .map(state -> new dev.mineagent.runtime.integrations.ysm.AppearanceCommitPlan.Selection(
                        state.modelId(), "-".equals(state.textureId()) ? "" : state.textureId(),
                        state.animationId()));
    }

    public String diagnosticCode() {
        if (!installed()) {
            return "YSM_ABSENT";
        }
        if (!version().startsWith("2.6.5-neoforge+mc26.1")) {
            return "YSM_VERSION_UNSUPPORTED";
        }
        if (!checksumVerified()) {
            return "YSM_CHECKSUM_MISMATCH";
        }
        return runtimeAvailable() ? "YSM_READY" : "YSM_NATIVE_UNAVAILABLE";
    }

    @Override
    public boolean apply(UUID playerId, String modelId, String textureId) {
        if (!runtimeAvailable() || !hasCommand("model", "set")) {
            return false;
        }
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        String texture = textureId == null || textureId.isBlank() ? "-" : textureId;
        String command = "ysm model set "
                + StringArgumentType.escapeIfRequired(player.getScoreboardName()) + " "
                + StringArgumentType.escapeIfRequired(modelId) + " "
                + StringArgumentType.escapeIfRequired(texture) + " true";
        int result = execute(command);
        if (result <= 0) {
            return false;
        }
        boolean applied = PinnedYsmReflection.readAppliedState(player, Thread.currentThread().getContextClassLoader())
                .filter(state -> modelId.equals(state.modelId()))
                .filter(state -> texture.equals("-") || texture.equals(state.textureId()))
                .isPresent();
        if (applied) {
            syncObservers(player);
        }
        return applied;
    }

    @Override
    public boolean playAnimation(UUID playerId, String animationId) {
        if (!runtimeAvailable() || animationId == null || animationId.isBlank() || !hasCommand("play")) {
            return false;
        }
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        String command = "ysm play "
                + StringArgumentType.escapeIfRequired(player.getScoreboardName()) + " "
                + StringArgumentType.escapeIfRequired(animationId);
        int result = execute(command);
        boolean applied = result > 0 && PinnedYsmReflection
                .readAppliedState(player, Thread.currentThread().getContextClassLoader())
                .filter(state -> animationId.equals(state.animationId()))
                .isPresent();
        if (applied) {
            syncObservers(player);
        }
        return applied;
    }

    private boolean hasCommand(String branch, String leaf) {
        var root = server.getCommands().getDispatcher().getRoot().getChild("ysm");
        if (root == null) {
            return false;
        }
        var branchNode = root.getChild(branch);
        return branchNode != null && branchNode.getChild(leaf) != null;
    }

    private boolean hasCommand(String branch) {
        var root = server.getCommands().getDispatcher().getRoot().getChild("ysm");
        return root != null && root.getChild(branch) != null;
    }

    private int execute(String command) {
        try {
            return server.getCommands().getDispatcher().execute(
                    command,
                    server.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS)
            );
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            return 0;
        }
    }

    private void syncObservers(net.minecraft.server.level.ServerPlayer target) {
        boolean sent = PinnedYsmReflection.syncPlayerState(
                target,
                server.getPlayerList().getPlayers().stream()
                        .filter(player -> !(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                        .toList(),
                Thread.currentThread().getContextClassLoader()
        );
        if (Boolean.getBoolean("mineagent.productionYsmSmokeTest")) {
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_YSM_OBSERVER_SYNC sent={} diagnostic={}", sent,
                    PinnedYsmReflection.syncDiagnostic());
        }
    }
}
