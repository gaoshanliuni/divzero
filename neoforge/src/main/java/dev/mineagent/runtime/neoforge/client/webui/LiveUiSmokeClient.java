package dev.mineagent.runtime.neoforge.client.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.agent.ui.UiAgentController;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.config.ServerConfigService;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.worker.provider.OpenAiCompatibleProvider;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;

/** Opt-in acceptance: real model output, actual game frame, then model-selected UI actions. No gameplay writes. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class LiveUiSmokeClient {
    private static final String TITLE = "协作工作台材料清单";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int ticks;
    private static boolean opened, backupAccepted, started, screenshot;
    private static volatile boolean captured;
    private static String viewId;
    private static UiAgentController controller;
    private static UiAgentController.Outcome outcome;
    private LiveUiSmokeClient() {}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("mineagent.liveUiSmokeTest")) return;
        Minecraft mc = Minecraft.getInstance(); ticks++;
        if (!backupAccepted && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
            backupAccepted = true; var f = backup.getClass().getDeclaredField("onProceed"); f.setAccessible(true);
            ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) f.get(backup)).proceed(false,false);
        }
        var host = WebGuiHostAdapter.INSTANCE;
        if (mc.player != null && !opened) { opened = true; host.open(); }
        if (host.ready() && viewId == null && UiClientSessions.current() != null) {
            Path base=Path.of(System.getProperty("mineagent.liveUiBase")).toAbsolutePath().normalize();
            Path run=Path.of(Files.readString(base.resolve("latest.txt")).strip()).toAbsolutePath().normalize();
            if(!run.startsWith(base)) throw new IllegalStateException("LIVE_PACKAGE_PATH");
            RuntimePackage pkg=JSON.readValue(Files.readString(run.resolve("package/manifest.json")),RuntimePackage.class);
            if(!RuntimePackageCanonicalizer.sha256(pkg).equals(pkg.canonicalSha256())) throw new IllegalStateException("LIVE_PACKAGE_HASH");
            var store=new ContentAddressedStore(run.resolve("content"));
            viewId=host.openPackagePreview(pkg,store::read,pkg.entrypoints().get("ui").path(),false);
            Files.writeString(mc.gameDirectory.toPath().resolve("live-ui-package-evidence.json"),JSON.writeValueAsString(Map.of(
                    "packageId",pkg.packageId(),"canonicalSha256",pkg.canonicalSha256(),"sourceRun",run.toString(),"preview",true)));
        }
        if(viewId!=null && host.packageLoaded(viewId) && !started) {
            started=true;
            try(var config=ServerConfigService.open(Path.of(System.getProperty("mineagent.liveUiConfig")))) {
                var values=config.snapshot().values();
                var provider=new OpenAiCompatibleProvider(URI.create(values.get("provider.openai.baseUrl")),
                        config.secretValue("provider.openai.apiKey").orElseThrow(),values.get("provider.openai.model"),Duration.ofSeconds(90));
                controller=new UiAgentController(PackagePageAgent.controlPreview(viewId),provider);
                controller.run("请把当前清单标题修改为「"+TITLE+"」，点击保存，并确认页面的标题已显示新名称。不要添加或删除材料。",
                        observation -> {
                            try { for(var element:JSON.readTree(observation).path("elements"))
                                if(element.path("role").asText().equals("heading") && element.path("label").asText().equals(TITLE)) return true;
                            } catch(Exception ignored) {} return false;
                        },6,Duration.ofMinutes(4)).whenComplete((result,failure)->mc.execute(()->{
                    if(failure!=null) throw new IllegalStateException("LIVE_UI_AGENT_FAILED",failure);
                    outcome=result;
                    try { Files.writeString(mc.gameDirectory.toPath().resolve("live-ui-agent-evidence.json"),JSON.writeValueAsString(result)); }
                    catch(Exception e){throw new IllegalStateException(e);}
                    if(!result.verified()) throw new IllegalStateException("LIVE_UI_AGENT_NOT_VERIFIED: "+result.status());
                    if(result.actions().stream().noneMatch(a -> a.contains("\"action\":\"click\"")))
                        throw new IllegalStateException("LIVE_UI_SAVE_CLICK_EVIDENCE_MISSING");
                    MineAgentRuntimeMod.LOGGER.info("MINEAGENT_LIVE_UI_AGENT_VERIFIED actions={} observations={}",result.actions().size(),result.observations().size());
                }));
            }
        }
        if(outcome!=null && outcome.verified() && !screenshot) {
            screenshot=true;
            if(!(mc.screen instanceof WebGuiInteractionScreen)) throw new IllegalStateException("LIVE_PREVIEW_NOT_VISIBLE");
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{
                try(image){image.writeToFile(mc.gameDirectory.toPath().resolve("live-ui-render.png"));captured=true;}
                catch(Exception e){throw new IllegalStateException(e);}
            });
        }
        if(captured){controller.close();host.close();MineAgentRuntimeMod.LOGGER.info("MINEAGENT_LIVE_UI_GRAPHICAL_OK businessWrites=false");mc.stop();}
        if(ticks>6000){if(controller!=null)controller.close();throw new IllegalStateException("LIVE_UI_SMOKE_TIMEOUT");}
    }
}
