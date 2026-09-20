package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.JsonObject;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.Files;

@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class UiSessionSmokeClient {
    private static int ticks, activeTicks;
    private static boolean opened, backupAccepted, screenshot;
    private static volatile boolean captured;
    private static JsonObject probe;
    private UiSessionSmokeClient() {}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("mineagent.uiSessionSmokeTest")) return;
        if(Boolean.getBoolean("mineagent.ysmJointSmoke")&&YsmJointSmokeClient.decisionsDone)return;
        Minecraft mc = Minecraft.getInstance(); ticks++;
        if (!backupAccepted && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
            backupAccepted = true;
            try {
                var f = backup.getClass().getDeclaredField("onProceed"); f.setAccessible(true);
                ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) f.get(backup)).proceed(false, false);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        if (mc.player != null && !opened) { opened = true; WebGuiHostAdapter.INSTANCE.open(); }
        var host = WebGuiHostAdapter.INSTANCE;
        if (host.ready() && UiClientSessions.current() != null) {
            activeTicks++;
            if (activeTicks % 20 == 0 && activeTicks < 200) host.browser().executeJavaScript("""
                (() => {
                  if (window.__decisionSmokeStarted) return;
                  document.querySelector('#open-decisions').click();
                  const card = [...document.querySelectorAll('[data-decision-id]')].find(n=>n.dataset.decisionId===DECISION); if (!card) return;
                  const option=card.querySelector('input[data-option-id="b"]');
                  const input=card.querySelector('textarea'); if(!option||!input) return;
                  window.__decisionSmokeStarted=true;
                  option.click(); input.focus();
                  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,'中文输入回归：保留数据');
                  input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'中文输入回归：保留数据'}));
                  window.__decisionSmokeBefore=input.value;
                  card.querySelector('[data-ai-id="decision-submit"]').click();
                })();
                """.replace("DECISION",new com.google.gson.Gson().toJson(String.valueOf(dev.mineagent.runtime.neoforge.ui.UiSessionSmokeServer.decisionId()))), host.browser().getURL(), 0);
            if (activeTicks % 20 == 0 && dev.mineagent.runtime.neoforge.ui.UiSessionSmokeServer.verified) {
                host.browser().executeJavaScript("""
                    window.mineagentQuery({request:JSON.stringify({channel:'sessionProbe',before:window.__decisionSmokeBefore,
                    status:[...document.querySelectorAll('[data-decision-id]')].find(n=>n.dataset.decisionId===DECISION)?.querySelector('[role="status"]')?.textContent,
                    cardCount:document.querySelectorAll('[data-decision-id]').length}),persistent:false,onSuccess:()=>{},onFailure:()=>{}});
                    """.replace("DECISION",new com.google.gson.Gson().toJson(String.valueOf(dev.mineagent.runtime.neoforge.ui.UiSessionSmokeServer.decisionId()))), host.browser().getURL(), 0);
            }
            if (probe != null && probe.get("status").getAsString().contains("服务器已确认") && !screenshot) {
                if (!(mc.screen instanceof WebGuiInteractionScreen)) throw new IllegalStateException("UI_VIEW_OBSCURED_BY_NATIVE_SCREEN: "
                        + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
                screenshot = true;
                try { Files.writeString(mc.gameDirectory.toPath().resolve("ui-session-dom-evidence.json"), probe.toString()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                    try (image) { image.writeToFile(mc.gameDirectory.toPath().resolve("ui-session-render.png")); captured = true; }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                });
            }
            if (captured) {
                if(Boolean.getBoolean("mineagent.ysmJointSmoke")){YsmJointSmokeClient.decisionsDone=true;return;}
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_SESSION_CLIENT_OK trueDom=true serverReadback=true duplicateWire=true");
                host.close(); mc.stop();
            }
        }
        if (ticks > 3600) throw new IllegalStateException("UI_SESSION_SMOKE_TIMEOUT: " + host.diagnostic());
    }
    static void accept(JsonObject message) { probe = message; }
}
