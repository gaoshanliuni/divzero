package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.Files;

/** Real game + live provider smoke. DOM test driver is not an AI planner and is labelled as such in evidence. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class PackageDeliverySmokeClient {
    private static int ticks;
    private static boolean opened, backup, observing, screenshot;
    private static volatile boolean captured;
    private static JsonObject probe;
    private static String observation;
    private static int observedAt;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("mineagent.packageDeliverySmoke")) return;
        Minecraft mc = Minecraft.getInstance(); ticks++;
        if (!backup && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen) {
            backup = true; var f = screen.getClass().getDeclaredField("onProceed"); f.setAccessible(true);
            ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) f.get(screen)).proceed(false, false);
        }
        var host = WebGuiHostAdapter.INSTANCE;
        if (mc.player != null && !opened) { opened = true; host.open(); }
        if (host.ready() && UiClientSessions.current() != null && ticks % 20 == 0) {
            host.browser().executeJavaScript("""
                (() => {
                  if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                  const agent=document.querySelector('#generation-agent');
                  const option=[...agent.options].find(o=>o.value===FIXTURE_AGENT_ID); if(!option)return;
                  if(!window.__packageSubmitted && !RESUME_ONLY) {
                    window.__packageSubmitted=true; agent.value=option.value;agent.dispatchEvent(new Event('change',{bubbles:true}));
                    const input=document.querySelector('#generation-prompt');
                    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,
                      '生成一个中文建造需求备忘录，标题为「建造需求备忘录」，可添加事项、选择高/中/低优先级、按优先级筛选，能编辑并保存标题。清爽深色布局，全部状态只在网页中，不请求外网，不伪造游戏数据。最多三个 HTML/CSS/JS 文件。');
                    input.dispatchEvent(new InputEvent('input',{bubbles:true}));document.querySelector('#generation-submit').click();
                  }
                  const jobs=[...document.querySelectorAll('.generation-job')].filter(j=>j.dataset.agentId===FIXTURE_AGENT_ID);
                  const published=jobs.find(j=>j.querySelector('strong').textContent.startsWith('PUBLISHED'));
                  if(published && !window.__packagePreviewRequested) {
                    window.__packagePreviewRequested=true;
                    if(!RESUME_ONLY)document.querySelector('#generation-submit').click(); // persistent duplicate request, not a new generation
                    published.querySelector('button').click();
                  }
                  const frame=[...document.querySelectorAll('iframe')].find(f=>f.title.includes('·'));
                  if(frame) window.mineagentQuery({request:JSON.stringify({channel:'packageDeliveryProbe',viewId:frame.name,
                    title:frame.title,jobCount:jobs.length,transferReceipt:published?.querySelector('.preview-receipt')?.textContent ?? '',
                    status:document.querySelector('#status').textContent}),persistent:false,onSuccess:()=>{},onFailure:()=>{}});
                })();
                """.replace("FIXTURE_AGENT_ID", new Gson().toJson(dev.mineagent.runtime.neoforge.ui.PackageDeliverySmokeServer.fixtureAgentId))
                        .replace("RESUME_ONLY", Boolean.toString(!System.getProperty("mineagent.packageDeliveryResume", "").isBlank())), host.browser().getURL(), 0);
        }
        if (probe != null && !probe.get("transferReceipt").getAsString().isBlank() && !observing && host.packageLoaded(probe.get("viewId").getAsString())) {
            observing = true;
            var port = PackagePageAgent.controlPreview(probe.get("viewId").getAsString());
            port.inspect().whenComplete((value, failure) -> mc.execute(() -> {
                port.cancel();
                if (failure != null) throw new IllegalStateException("PACKAGE_DELIVERY_DOM_OBSERVATION_FAILED", failure);
                try {
                    var root = mc.gameDirectory.toPath().resolve("package-delivery-evidence"); Files.createDirectories(root);
                    Files.writeString(root.resolve("dom.json"), value);
                } catch(Exception e) { throw new IllegalStateException(e); }
                if (!containsTitle(value)) throw new IllegalStateException("GENERATED_TITLE_NOT_RENDERED");
                observation = value;
                observedAt = ticks;
            }));
        }
        if (observation != null && ticks - observedAt >= 10 && dev.mineagent.runtime.neoforge.ui.PackageDeliverySmokeServer.verified && !screenshot) {
            if (!(mc.screen instanceof WebGuiInteractionScreen)) throw new IllegalStateException("GENERATED_VIEW_OBSCURED");
            screenshot = true;
            var root = mc.gameDirectory.toPath().resolve("package-delivery-evidence"); Files.createDirectories(root);
            Files.writeString(root.resolve("dom.json"), observation);
            probe.addProperty("driver", "TRUSTED_PAGE_DOM_TEST_DRIVER_NOT_AI_PLANNER");
            Files.writeString(root.resolve("client.json"), probe.toString());
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                try (image) { image.writeToFile(root.resolve("render.png")); captured = true; }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
        }
        if (captured) { MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PACKAGE_DELIVERY_GRAPHICAL_OK networkDownload=true businessWrites=false"); host.close(); mc.stop(); }
        if (ticks > 7200) throw new IllegalStateException("PACKAGE_DELIVERY_SMOKE_TIMEOUT: " + host.diagnostic());
    }
    static void accept(JsonObject value) { probe = value; }
    static boolean containsTitle(String value) {
        for (var element : JsonParser.parseString(value).getAsJsonObject().getAsJsonArray("elements")) {
            var e = element.getAsJsonObject();
            if (!e.has("visible") || !e.get("visible").getAsBoolean()) continue;
            String role=e.get("role").getAsString(), label=e.get("label").getAsString();
            if (role.equals("heading") && label.contains("建造需求备忘录")) return true;
            if ((role.equals("textbox") || role.equals("input")) && label.contains("标题") && e.has("value") && e.get("value").getAsString().equals("建造需求备忘录")) return true;
        }
        return false;
    }
}
