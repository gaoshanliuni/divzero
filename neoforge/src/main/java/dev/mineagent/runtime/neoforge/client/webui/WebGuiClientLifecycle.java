package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEF;
import com.google.gson.JsonObject;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = MineAgentRuntimeMod.MOD_ID, value = Dist.CLIENT)
public final class WebGuiClientLifecycle {
    private static int ticks;
    private static int readyTicks;
    private static boolean opened;
    private static boolean captured;
    private static volatile boolean captureComplete;
    private static JsonObject probe;
    private static final String OPERATION = UUID.randomUUID().toString();
    private WebGuiClientLifecycle() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        HudPersistenceClient.tick();
        var host = WebGuiHostAdapter.INSTANCE;
        host.tick();
        WorldUiClient.tick();
        if (!Boolean.getBoolean("mineagent.webguiSmokeTest")) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (!opened && ticks > 30 && MCEF.isInitialized()) { opened = true; host.open(); }
        if (Boolean.getBoolean("mineagent.themeControlsSmoke")) { ThemeSmokeClient.tick(); return; }
        if (host.ready()) {
            readyTicks++;
            if(Boolean.getBoolean("mineagent.textureRetirementSmoke")&&readyTicks>=24&&readyTicks<=44&&readyTicks%4==0){
                int inset=readyTicks%8==0?96:0;host.browser().resize(mc.getWindow().getWidth()-inset,mc.getWindow().getHeight()-inset);
            }
            if (readyTicks == 20) {
                host.browser().executeJavaScript("document.querySelector('#echo-input').value='WebGUI 本地回读';"
                        + "document.querySelector('#echo-submit').click();"
                        + "document.querySelector('#open-chat').click();"
                        + "const before=document.querySelectorAll('.window').length;"
                        + "document.querySelector('[aria-label=\"AI 对话\"] button[aria-label=\"关闭\"]').click();"
                        + "window.__independentClose=before===2 && document.querySelectorAll('.window').length===1;"
                        + "window.cefQuery({request:JSON.stringify({channel:'close'}),persistent:false,"
                        + "onSuccess:()=>{window.__defaultBridgeBlocked=false},"
                        + "onFailure:()=>{window.__defaultBridgeBlocked=true}});", host.browser().getURL(), 0);
            }
            if (readyTicks == 40) host.emit("probe", Map.of("operationId", OPERATION));
            if (probe != null && !captured && readyTicks > 50) {
                captured = true;
                if(Boolean.getBoolean("mineagent.textureRetirementSmoke")){
                    if(McefTextureRetirement.textures()<2||McefTextureRetirement.views()<2||McefTextureRetirement.pending()!=0)
                        throw new IllegalStateException("MCEF_TEXTURE_RETIREMENT_NOT_EXERCISED");
                    probe.addProperty("retiredTextures",McefTextureRetirement.textures());probe.addProperty("retiredTextureViews",McefTextureRetirement.views());probe.addProperty("pendingRetirements",McefTextureRetirement.pending());
                }
                if (!dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("WebGUI 本地回读").equals(probe.get("echo").getAsString())
                        || !probe.get("defaultBridgeBlocked").getAsBoolean()
                        || !probe.get("independentClose").getAsBoolean() || !probe.get("titlebarVisible").getAsBoolean()
                        || probe.getAsJsonArray("controls").isEmpty() || !host.browser().isTextureReady()) {
                    MineAgentRuntimeMod.LOGGER.error("MINEAGENT_WEBGUI_SMOKE_FAILED");
                    host.close(); throw new IllegalStateException("MINEAGENT_WEBGUI_SMOKE_FAILED");
                }
                try {
                    probe.addProperty("executionMode", "DOM_HOST_ECHO_NOT_GAME_BUSINESS");
                    probe.addProperty("browser", "WebGUI 1.6.2 / MCEF 2.2.0 / in-game Chromium");
                    probe.addProperty("nativeVersion", MCEF.getApp().getHandle().getVersion().toString());
                    probe.addProperty("pageReadyMillis", host.readyMillis());
                    probe.addProperty("domSummaryBytes", 0);
                    for (int i = 0; i < 3; i++) probe.addProperty("domSummaryBytes", probe.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    Files.writeString(mc.gameDirectory.toPath().resolve("webgui-dom-evidence.json"), probe.toString());
                } catch (Exception failure) { throw new IllegalStateException("Could not save WebGUI smoke evidence", failure); }
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                    try (image) {
                        image.writeToFile(mc.gameDirectory.toPath().resolve("webgui-render-evidence.png"));
                        captureComplete = true;
                    } catch (Exception failure) { MineAgentRuntimeMod.LOGGER.error("WEBGUI_SCREENSHOT_FAILED", failure); }
                });
            }
            if (captureComplete) {
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WEBGUI_LOCAL_PAGE_OK operationId={} readyTicks={}", OPERATION, readyTicks);
                host.close(); mc.stop();
            }
        }
        if (ticks > 2400) {
            String reason = host.diagnostic();
            host.close();
            throw new IllegalStateException("MINEAGENT_WEBGUI_SMOKE_TIMEOUT status=" + reason);
        }
    }

    static void acceptProbe(JsonObject value) {
        if (OPERATION.equals(value.get("operationId").getAsString())) probe = value;
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { HudPersistenceClient.flushWrites();ClientScriptPackages.connectionClosed();WebGuiHostAdapter.INSTANCE.close(); }
}
