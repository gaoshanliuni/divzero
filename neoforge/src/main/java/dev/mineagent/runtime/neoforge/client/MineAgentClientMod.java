package dev.mineagent.runtime.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import org.lwjgl.glfw.GLFW;

@Mod(value = MineAgentRuntimeMod.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MineAgentRuntimeMod.MOD_ID, value = Dist.CLIENT)
public final class MineAgentClientMod {
    private static int smokeTicks;
    private static int integratedWaitTicks;
    private static boolean integratedProceedAttempted;
    private static boolean integratedJoined;
    private static boolean multiplayerJoined;
    private static int multiplayerWaitTicks;
    private static boolean ysmUiSubmitted;
    private static boolean ysmUiVerified;
    private static String ysmSmokeAgentId = "";
    private static boolean ysmScreenshotRequested;
    private static volatile String ysmScreenshotHash = "";
    private static volatile int ysmScreenshotColors;
    private static long ysmMemoryBefore;
    private static boolean ysmWorldObservationStarted;
    private static int ysmInteractionStage;
    private static String ysmStaleRequestId = "";
    private static String ysmDuplicateRequestId = "";
    private static boolean ysmMultiplayerObservationStarted;
    private static boolean ysmMultiplayerObservationVerified;
    private static boolean ysmRestartObservationStarted;
    private static boolean ysmRestartObservationVerified;
    private static long packageTick;
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(MineAgentRuntimeMod.MOD_ID, "control_center")
    );

    private static final KeyMapping OPEN_CONTROL_CENTER = new KeyMapping(
            "key.mineagent_runtime.open_control_center",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY
    );

    public static final KeyMapping TOGGLE_WORKSPACE = new KeyMapping("key.mineagent_runtime.toggle_workspace",KeyConflictContext.UNIVERSAL,KeyModifier.NONE,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_F2,CATEGORY);
    public MineAgentClientMod(ModContainer container) {
        // Minecraft Main defaults AWT to headless. Only the explicit OS-input fixture needs Robot.
        if(Boolean.getBoolean("mineagent.workspaceSmoke")||Boolean.getBoolean("mineagent.viewSettingsSmoke"))System.setProperty("java.awt.headless","false");
        try {
            dev.mineagent.runtime.client.webui.WebGuiProfile.ensureDefaults(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("config/mcef/mcef.properties"));
        } catch (java.io.IOException failure) {
            MineAgentRuntimeMod.LOGGER.error("Could not create WebGUI first-install profile", failure);
        }
        dev.mineagent.runtime.neoforge.client.ysm.YsmRenderFallback.initialize();
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (ignored, parent) -> ControlCenterScreen.create(parent));
    }

    @SubscribeEvent
    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_CONTROL_CENTER);
        event.register(TOGGLE_WORKSPACE);
    }

    @SubscribeEvent
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MineAgentRegistries.RUNTIME_OBJECT.get(),dev.mineagent.runtime.neoforge.client.objects.RuntimeObjectRenderer::new);
        event.registerEntityRenderer(MineAgentRegistries.BASKETBALL_ENTITY.get(), ThrownItemRenderer::new);
        event.registerEntityRenderer(MineAgentRegistries.SENTINEL_BOSS.get(),
                context -> (net.minecraft.client.renderer.entity.EntityRenderer) new ZombieRenderer(context));
        event.registerBlockEntityRenderer(MineAgentRegistries.MEDIA_SCREEN_BLOCK_ENTITY.get(),
                context -> new dev.mineagent.runtime.neoforge.client.media.MediaScreenRenderer());
    }

    @SubscribeEvent
    static void desktopChatLayer(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre event) {
        // Keep vanilla messages in history, but do not draw duplicate lettering underneath the translucent desktop.
        if (event.getName().equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT)
                && Minecraft.getInstance().screen instanceof dev.mineagent.runtime.neoforge.client.webui.WebGuiInteractionScreen
                && dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.ready()) event.setCanceled(true);
    }

    @SubscribeEvent
    static void afterClientTick(ClientTickEvent.Post event) {
        dev.mineagent.runtime.neoforge.client.MineAgentClientPackages.tick(++packageTick);
        dev.mineagent.runtime.neoforge.client.webui.WebGuiWorkspaceInput.tick();
        while (OPEN_CONTROL_CENTER.consumeClick()) {
            dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.open();
        }
        if (Boolean.getBoolean("mineagent.clientSmokeTest")) {
            runSmokeTest();
        }
    }

    private static void runSmokeTest() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean integrated = Boolean.getBoolean("mineagent.integratedSmokeTest");
        boolean multiplayer = Boolean.getBoolean("mineagent.multiplayerSmokeClient");
        if (multiplayer && minecraft.player == null) {
            if (multiplayerJoined && minecraft.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_MULTIPLAYER_CLIENT_OK");
                minecraft.stop();
            }
            if (++multiplayerWaitTicks >= 600) {
                MineAgentRuntimeMod.LOGGER.error("MINEAGENT_MULTIPLAYER_CONNECT_TIMEOUT");
                minecraft.stop();
            }
            return;
        }
        if (integrated && minecraft.player == null) {
            if (integratedJoined && minecraft.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
                if (Boolean.getBoolean("mineagent.productionYsmSmokeTest") && !ysmUiVerified) {
                    MineAgentRuntimeMod.LOGGER.error("MINEAGENT_SMOKE_YSM_UI_APPLY_MISSING");
                }
                if (Boolean.getBoolean("mineagent.productionYsmRestartSmokeTest")
                        && !ysmRestartObservationVerified) {
                    MineAgentRuntimeMod.LOGGER.error("MINEAGENT_SMOKE_YSM_RESTART_OBSERVER_MISSING");
                }
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_INTEGRATED_CLIENT_OK");
                minecraft.stop();
                return;
            }
            integratedWaitTicks++;
            if (!integratedProceedAttempted
                    && minecraft.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
                integratedProceedAttempted = true;
                try {
                    var field = net.minecraft.client.gui.screens.BackupConfirmScreen.class
                            .getDeclaredField("onProceed");
                    field.setAccessible(true);
                    ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) field.get(backup))
                            .proceed(false, false);
                    MineAgentRuntimeMod.LOGGER.info("MINEAGENT_INTEGRATED_ACCEPTED_TEST_WORLD_BACKUP_PROMPT");
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not continue integrated smoke world", failure);
                }
            }
            if (integratedWaitTicks % 100 == 0) {
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_INTEGRATED_WAIT screen={}",
                        minecraft.screen == null ? "null" : minecraft.screen.getClass().getName());
            }
            if (integratedWaitTicks >= 600) {
                MineAgentRuntimeMod.LOGGER.error("MINEAGENT_INTEGRATED_TIMEOUT screen={}",
                        minecraft.screen == null ? "null" : minecraft.screen.getClass().getName());
                minecraft.stop();
            }
            return;
        }
        if (integrated) {
            integratedJoined = true;
        }
        if (multiplayer) {
            multiplayerJoined = true;
        }
        smokeTicks++;
        if (Boolean.getBoolean("mineagent.productionYsmRestartSmokeTest")) {
            verifyRestartYsmObserver(minecraft);
            return;
        }
        int integratedDeadline = Boolean.getBoolean("mineagent.productionYsmSmokeTest") ? 650 : 120;
        if (multiplayer && Boolean.getBoolean("mineagent.productionYsmMultiplayerSmokeTest")) {
            verifyMultiplayerYsmObserver(minecraft);
        }
        if (Boolean.getBoolean("mineagent.productionYsmSmokeTest") && ysmUiVerified
                && smokeTicks < integratedDeadline && runYsmInteractionSmoke(minecraft)) {
            return;
        }
        if (smokeTicks == 20) {
            minecraft.setScreen(ControlCenterScreen.create(minecraft.screen));
        } else if (smokeTicks == 30 && minecraft.screen instanceof ControlCenterScreen controlCenter) {
            controlCenter.runAllPageLayoutSmoke();
            MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SMOKE_ALL_UI_PAGES_OK count=15");
        } else if (smokeTicks == 40) {
            if (!(minecraft.screen instanceof ControlCenterScreen)) {
                throw new IllegalStateException("MineAgent Control Center did not open");
            }
            MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SMOKE_CLIENT_PANEL_OK size={}x{}",
                    minecraft.screen.width, minecraft.screen.height);
        } else if (Boolean.getBoolean("mineagent.productionYsmSmokeTest") && smokeTicks > 40 && !ysmUiSubmitted
                && minecraft.screen instanceof ControlCenterScreen controlCenter) {
            var values = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();
            if (controlCenter.runYsmAppearanceUiSmoke("default", "blue", "idle")) {
                ysmUiSubmitted = true;
                ysmSmokeAgentId = values.getOrDefault("agent.0.id", "");
                dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.expect(
                        java.util.UUID.fromString(ysmSmokeAgentId));
                ysmMemoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                MineAgentRuntimeMod.LOGGER.info(
                        "MINEAGENT_SMOKE_YSM_UI_SUBMITTED model=default texture=blue animation=idle");
            }
        } else if (Boolean.getBoolean("mineagent.productionYsmSmokeTest") && ysmUiSubmitted && !ysmUiVerified
                && smokeTicks < (integrated ? integratedDeadline : 60)) {
            var state = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.appearanceState();
            var values = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();
            if (!state.accepted() && !"NOT_RUN".equals(state.errorCode())) {
                MineAgentRuntimeMod.LOGGER.error(
                        "MINEAGENT_SMOKE_YSM_UI_APPLY_REJECTED code={} revision={}",
                        state.errorCode(), state.revision());
                minecraft.stop();
                return;
            }
            if (state.accepted()
                    && Boolean.parseBoolean(values.getOrDefault("ysm.runtimeAvailable", "false"))
                    && "default".equals(values.get("agent." + ysmSmokeAgentId + ".model"))
                    && "blue".equals(values.get("agent." + ysmSmokeAgentId + ".texture"))
                    && "idle".equals(values.get("agent." + ysmSmokeAgentId + ".animation"))) {
                if (!ysmWorldObservationStarted) {
                    ysmWorldObservationStarted = true;
                    minecraft.setScreen(null);
                    dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.expect(
                            java.util.UUID.fromString(ysmSmokeAgentId));
                    return;
                }
                var render = dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.snapshot();
                var observed = dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver
                        .inspectExpectedClientState().orElse(render.clientState().orElse(null));
                if (smokeTicks % 40 == 0) {
                    MineAgentRuntimeMod.LOGGER.info(
                            "MINEAGENT_YSM_RENDER_WAIT renderFrames={} ysmCalls={} vanillaCalls={} callbacks={} "
                                    + "duplicateDraw={} duplicateCallback={} cacheSize={} cacheCapacity={} "
                                    + "cacheOverflow={} observer={} fallback={}",
                            render.uniqueFrames(), render.ysmCalls(), render.vanillaCalls(), render.callbackCount(),
                            render.duplicateDraws(), render.duplicateCallbacks(), render.boundedCacheSize(),
                            render.cacheCapacity(), render.cacheOverflowCount(), observed,
                            dev.mineagent.runtime.neoforge.client.ysm.YsmRenderFallback.diagnostic());
                }
                boolean observedState = observed != null && observed.renderReady()
                        && "default".equals(observed.modelId())
                        && "blue".equals(observed.textureId()) && "idle".equals(observed.animationId());
                if (render.ysmCalls() >= 3 && render.vanillaCalls() == 0 && observedState
                        && !ysmScreenshotRequested) {
                    ysmScreenshotRequested = true;
                    captureYsmSmokeScreenshot(minecraft);
                }
                if (!ysmScreenshotHash.isBlank()) {
                    long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    long growth = Math.max(0, used - ysmMemoryBefore);
                    boolean accountingValid = render.uniqueFrames() > 0 && render.ysmCalls() >= 3
                            && render.vanillaCalls() == 0 && render.callbackCount() >= render.ysmCalls()
                            && render.duplicateDraws() == 0 && render.duplicateCallbacks() == 0
                            && render.boundedCacheSize() <= render.cacheCapacity()
                            && render.cacheOverflowCount() == 0;
                    if (!accountingValid || render.averageFrameMillis() > 250
                            || growth > 512L * 1024 * 1024) {
                        throw new IllegalStateException("YSM render performance/resource smoke exceeded bound");
                    }
                    ysmUiVerified = true;
                    MineAgentRuntimeMod.LOGGER.info(
                            "MINEAGENT_SMOKE_YSM_RENDER_FRAME_OK entity={} renderFrames={} ysmCalls={} "
                                    + "vanillaCalls={} callbacks={} duplicateDraw={} duplicateCallback={} "
                                    + "cacheSize={} cacheCapacity={} cacheOverflow={} observer=default/blue/idle "
                                    + "screenshotSha256={} colors={} avgFrameMs={} memoryGrowth={}",
                            ysmSmokeAgentId, render.uniqueFrames(), render.ysmCalls(), render.vanillaCalls(),
                            render.callbackCount(), render.duplicateDraws(), render.duplicateCallbacks(),
                            render.boundedCacheSize(), render.cacheCapacity(), render.cacheOverflowCount(),
                            ysmScreenshotHash, ysmScreenshotColors, render.averageFrameMillis(), growth);
                    MineAgentRuntimeMod.LOGGER.info(
                            "MINEAGENT_SMOKE_YSM_UI_APPLY_OK model=default texture=blue animation=idle revision={}",
                            state.revision());
                }
            }
        } else if (smokeTicks >= (integrated ? integratedDeadline : multiplayer ? 500 : 60)) {
            if (Boolean.getBoolean("mineagent.productionYsmSmokeTest") && !ysmUiVerified) {
                MineAgentRuntimeMod.LOGGER.error("MINEAGENT_SMOKE_YSM_UI_APPLY_MISSING");
            }
            if (multiplayer) {
                if (Boolean.getBoolean("mineagent.productionYsmMultiplayerSmokeTest")
                        && !ysmMultiplayerObservationVerified) {
                    MineAgentRuntimeMod.LOGGER.error("MINEAGENT_MULTIPLAYER_YSM_OBSERVER_MISSING");
                }
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_MULTIPLAYER_CLIENT_OK");
            }
            minecraft.stop();
        }
    }

    private static void captureYsmSmokeScreenshot(Minecraft minecraft) {
        net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> {
            try (image) {
                int[] pixels = image.getPixels();
                var colors = new java.util.HashSet<Integer>();
                int step = Math.max(1, pixels.length / 16_384);
                for (int index = 0; index < pixels.length; index += step) {
                    colors.add(pixels[index]);
                    if (colors.size() >= 256) {
                        break;
                    }
                }
                if (colors.size() < 16) {
                    MineAgentRuntimeMod.LOGGER.error("MINEAGENT_SMOKE_YSM_SCREENSHOT_FLAT colors={}", colors.size());
                    return;
                }
                java.nio.file.Path screenshot = minecraft.gameDirectory.toPath()
                        .resolve("ysm-render-evidence.png");
                image.writeToFile(screenshot);
                ysmScreenshotColors = colors.size();
                ysmScreenshotHash = dev.mineagent.runtime.core.packages.ContentPackageService.sha256(
                        java.nio.file.Files.readAllBytes(screenshot));
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.error("MINEAGENT_SMOKE_YSM_SCREENSHOT_FAILED", failure);
            }
        });
    }

    private static boolean runYsmInteractionSmoke(Minecraft minecraft) {
        var appearance = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.appearanceState();
        var values = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();
        if (ysmInteractionStage == 0) {
            minecraft.setScreen(ControlCenterScreen.create(minecraft.screen,
                    dev.mineagent.runtime.api.config.PanelSection.APPEARANCE));
            ysmInteractionStage = 1;
            return true;
        }
        if (ysmInteractionStage == 1 && minecraft.screen instanceof ControlCenterScreen screen
                && screen.runYsmChoiceCardOpenSmoke()) {
            ysmInteractionStage = 2;
            return true;
        }
        if (ysmInteractionStage == 2 && minecraft.screen instanceof ControlCenterScreen screen
                && screen.runYsmFirstChoiceSubmitSmoke()) {
            ysmInteractionStage = 3;
            return true;
        }
        if (ysmInteractionStage == 3 && appearance.accepted() && appearance.revision() >= 2) {
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_YSM_SELECTION_CARD_OK agent={} revision={}",
                    ysmSmokeAgentId, appearance.revision());
            minecraft.setScreen(ControlCenterScreen.create(minecraft.screen,
                    dev.mineagent.runtime.api.config.PanelSection.APPEARANCE));
            ysmInteractionStage = 4;
            return true;
        }
        if (ysmInteractionStage == 4 && minecraft.screen instanceof ControlCenterScreen screen
                && screen.runYsmChoiceCardOpenSmoke()) {
            ysmInteractionStage = 5;
            return true;
        }
        if (ysmInteractionStage == 5
                && "AI 外观选择".equals(
                dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.decisionState().values().get("title"))
                && "OPEN".equals(
                dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.decisionState().values().get("status"))) {
            minecraft.getConnection().sendChat("模型=default 贴图=blue 动画=idle");
            ysmInteractionStage = 6;
            return true;
        }
        if (ysmInteractionStage == 6 && appearance.accepted() && appearance.revision() >= 3) {
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_YSM_CHAT_EQUIVALENCE_OK agent={} revision={}",
                    ysmSmokeAgentId, appearance.revision());
            ysmStaleRequestId = java.util.UUID.randomUUID().toString();
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                    new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.AppearanceCommand(
                            ysmSmokeAgentId, "default", "blue", "idle", 0, ysmStaleRequestId));
            ysmInteractionStage = 7;
            return true;
        }
        if (ysmInteractionStage == 7 && ysmStaleRequestId.equals(appearance.requestId())
                && "STALE_REVISION".equals(appearance.errorCode()) && appearance.revision() >= 3) {
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_YSM_STALE_REJECTED_OK revision={}", appearance.revision());
            ysmDuplicateRequestId = java.util.UUID.randomUUID().toString();
            long revision = Long.parseLong(values.getOrDefault(
                    "agent." + ysmSmokeAgentId + ".appearanceRevision", "3"));
            var duplicate = new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.AppearanceCommand(
                    ysmSmokeAgentId, "default", "blue", "idle", revision, ysmDuplicateRequestId);
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(duplicate);
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(duplicate);
            ysmInteractionStage = 8;
            return true;
        }
        if (ysmInteractionStage == 8 && ysmDuplicateRequestId.equals(appearance.requestId())
                && appearance.accepted() && appearance.revision() >= 4) {
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_YSM_IDEMPOTENT_CLIENT_OK requestId={} revision={}",
                    ysmDuplicateRequestId, appearance.revision());
            ysmInteractionStage = 9;
            return false;
        }
        return ysmInteractionStage < 9;
    }

    private static void verifyMultiplayerYsmObserver(Minecraft minecraft) {
        var values = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();
        if (!ysmMultiplayerObservationStarted) {
            int count;
            try {
                count = Integer.parseInt(values.getOrDefault("agent.count", "0"));
            } catch (NumberFormatException invalid) {
                return;
            }
            for (int index = 0; index < Math.min(count, 4); index++) {
                String id = values.get("agent." + index + ".id");
                if (id != null && "default".equals(values.get("agent." + id + ".model"))) {
                    dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.expect(java.util.UUID.fromString(id));
                    minecraft.setScreen(ControlCenterScreen.create(minecraft.screen,
                            dev.mineagent.runtime.api.config.PanelSection.APPEARANCE));
                    ysmMultiplayerObservationStarted = true;
                    return;
                }
            }
            return;
        }
        if (ysmMultiplayerObservationVerified) {
            return;
        }
        var render = dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.snapshot();
        var state = dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.inspectExpectedClientState().orElse(null);
        if (render.ysmCalls() >= 3 && render.vanillaCalls() == 0 && state != null && state.renderReady()
                && "default".equals(state.modelId()) && "blue".equals(state.textureId())
                && "idle".equals(state.animationId())) {
            ysmMultiplayerObservationVerified = true;
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_MULTIPLAYER_YSM_OBSERVER_OK player={} entity={} ysmCalls={} vanillaCalls=0",
                    minecraft.getUser().getName(), render.entityId(), render.ysmCalls());
        }
    }

    private static void verifyRestartYsmObserver(Minecraft minecraft) {
        if (ysmRestartObservationVerified) {
            return;
        }
        var values = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();
        if (!ysmRestartObservationStarted) {
            int count;
            try {
                count = Integer.parseInt(values.getOrDefault("agent.count", "0"));
            } catch (NumberFormatException invalid) {
                return;
            }
            for (int index = 0; index < Math.min(count, 4); index++) {
                String id = values.get("agent." + index + ".id");
                if (id != null && "default".equals(values.get("agent." + id + ".model"))
                        && "4".equals(values.get("agent." + id + ".appearanceRevision"))) {
                    dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.expect(java.util.UUID.fromString(id));
                    minecraft.setScreen(ControlCenterScreen.create(minecraft.screen,
                            dev.mineagent.runtime.api.config.PanelSection.APPEARANCE));
                    ysmRestartObservationStarted = true;
                    return;
                }
            }
            return;
        }
        var render = dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.snapshot();
        var state = dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.inspectExpectedClientState().orElse(null);
        if (render.ysmCalls() >= 3 && render.vanillaCalls() == 0 && state != null && state.renderReady()
                && "default".equals(state.modelId()) && "blue".equals(state.textureId())
                && "idle".equals(state.animationId())) {
            ysmRestartObservationVerified = true;
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_YSM_RESTART_OBSERVER_OK entity={} revision=4 ysmCalls={} vanillaCalls=0",
                    render.entityId(), render.ysmCalls());
        }
    }
}
