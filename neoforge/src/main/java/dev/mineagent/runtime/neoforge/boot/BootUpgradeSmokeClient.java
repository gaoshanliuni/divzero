package dev.mineagent.runtime.neoforge.boot;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.client.webui.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.*;
import java.util.*;

/** Real semi-transparent F2 flow; uses DOM JavaScript only and never OS input. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class BootUpgradeSmokeClient {
    private static final Gson JSON = new Gson();
    private static JsonObject probe;
    private static int ticks, phase;
    private static boolean backup, trusted, trustPressed, opened, finished, glass;

    private BootUpgradeSmokeClient() {}

    public static void accept(JsonObject value) {
        if (!BootUpgradeSmokeServer.enabled()) return;
        probe = value;
        glass |= text("cardBackground").startsWith("rgba(");
    }

    private static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("boot-upgrade-smoke");
    }

    private static void write(String name, Object value) throws Exception {
        Files.createDirectories(root());
        Files.writeString(root().resolve(name + ".json"), JSON.toJson(value));
    }

    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }

    private static String text(String key) {
        return probe == null || !probe.has(key) ? "" : probe.get(key).getAsString();
    }

    private static void script(String source) {
        var host = WebGuiHostAdapter.INSTANCE;
        host.browser().executeJavaScript("(()=>{" + source + "})();", host.browser().getURL(), 0);
    }

    private static void action(String key, String source) {
        script("window.__bootUpgradeSmoke??={};if(window.__bootUpgradeSmoke[" + JSON.toJson(key)
                + "])return;const ok=(()=>{" + source + "})();if(ok)window.__bootUpgradeSmoke["
                + JSON.toJson(key) + "]=true;");
    }

    private static void poll() {
        script("window.dispatchEvent(new Event('mineagent:boot-upgrade-probe'));");
    }

    private static void finish(String status, Map<String, Object> extra) throws Exception {
        var values = new LinkedHashMap<String, Object>(extra);
        values.put("status", status);
        values.put("stage", BootUpgradeSmokeServer.stage());
        values.put("glass", glass);
        values.put("providerCalls", BootUpgradeSmokeServer.stage().equals("upgrade") ? 1 : 0);
        values.put("systemInputInjected", false);
        values.put("fullV1", false);
        write("client-" + BootUpgradeSmokeServer.stage(), values);
        finished = true;
        dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                "MINEAGENT_BOOT_UPGRADE_{}_OK", BootUpgradeSmokeServer.stage().toUpperCase(Locale.ROOT));
        WebGuiHostAdapter.INSTANCE.close();
        Minecraft.getInstance().stop();
    }

    private static void fail(Throwable failure) throws Exception {
        finished = true;
        write("client-failure-" + BootUpgradeSmokeServer.stage(), Map.of(
                "stage", BootUpgradeSmokeServer.stage(), "phase", phase, "ticks", ticks,
                "error", failure.toString(), "serverFailure", Objects.toString(BootUpgradeSmokeServer.failure, ""),
                "probe", probe == null ? "{}" : probe.toString()));
        WebGuiHostAdapter.INSTANCE.close();
        Minecraft.getInstance().stop();
    }

    private static String catalogButton(String label) {
        return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-catalog');"
                + "const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="
                + JSON.toJson(BootUpgradeSmokeServer.NAME) + ");const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==="
                + JSON.toJson(label) + ");if(!b)return false;b.click();return true;";
    }

    private static String stageBuild(String key) {
        return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');"
                + "const c=[...p?.querySelectorAll('article')||[]].find(n=>n.innerText.includes('BUILT')&&n.innerText.includes('替换前驱')&&[...n.querySelectorAll('button')].some(b=>b.textContent==='批准并暂存停机替换计划'));"
                + "const q=c?.querySelector('input[type=checkbox]');"
                + "const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='批准并暂存停机替换计划');"
                + "if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;";
    }

    private static void prepare(String catalog, String boot) throws Exception {
        switch (phase) {
            case 0 -> {
                action("prepare-catalog", "document.querySelector('#open-package-catalog')?.click();return true;");
                if (catalog.contains(BootUpgradeSmokeServer.NAME)) phase = 1;
            }
            case 1 -> {
                action("prepare-open-boot", catalogButton("构建 / 安装启动扩展"));
                if (boot.contains("待构建：" + BootUpgradeSmokeServer.NAME)) phase = 2;
            }
            case 2 -> {
                action("prepare-build", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='构建此 BOOT_EXTENSION');if(!b||b.disabled)return false;b.click();return true;");
                if (boot.contains(" · BUILT · ")) phase = 3;
            }
            case 3 -> {
                action("prepare-install", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.innerText.includes(' · BUILT · ')&&[...n.querySelectorAll('button')].some(b=>b.textContent==='全局安装，下一次重启加载'));const q=c?.querySelector('input[type=checkbox]');const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='全局安装，下一次重启加载');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");
                if (boot.contains("INSTALLED_PENDING_RESTART") && boot.contains("文件 HASH_MATCHED")
                        && boot.contains("NOT_LOADED")) {
                    require(glass, "BOOT_UPGRADE_GLASS");
                    finish("REAL_CEF_BOOT_V1_BUILD_INSTALL_PENDING_RESTART_VERIFIED", Map.of(
                            "head", BootUpgradeSmokeServer.runtimePackage, "downloadOrHotLoad", false,
                            "markerExistsBeforeRestart", Files.exists(Minecraft.getInstance().gameDirectory.toPath()
                                    .resolve("boot-upgrade-runtime.txt"))));
                }
            }
        }
    }

    private static void upgrade(String catalog, String boot, String tools, String generation,
                                String patch, String review) throws Exception {
        switch (phase) {
            case 0 -> {
                action("upgrade-catalog", "document.querySelector('#open-package-catalog')?.click();return true;");
                if (catalog.contains(BootUpgradeSmokeServer.NAME)) phase = 1;
            }
            case 1 -> {
                action("upgrade-tools", catalogButton("打开现有操作"));
                if (tools.contains(BootUpgradeSmokeServer.NAME)) phase = 2;
            }
            case 2 -> {
                action("upgrade-open-patch", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-tools');const s=p?.querySelector('select');if(!s||s.options.length<2)return false;s.value=s.options[1].value;s.dispatchEvent(new Event('change',{bubbles:true}));const b=[...p.querySelectorAll('button')].find(n=>n.textContent==='修改原生代码与资源');if(!b||b.disabled)return false;b.click();return true;");
                if (patch.contains("生成同包改版候选")) phase = 3;
            }
            case 3 -> {
                action("upgrade-submit-patch", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-world-patch');const t=p?.querySelector('#world-patch-prompt');const b=p?.querySelector('#world-patch-submit');if(!t||!b||b.disabled)return false;t.value='把启动标记从 V1 改为 V2，并把包版本提升为 2.0.0；保持 modId、入口、权限和兼容声明。';t.dispatchEvent(new Event('input',{bubbles:true}));b.click();return true;");
                action("upgrade-open-generation", "document.querySelector('#open-generation')?.click();return true;");
                if (generation.contains("READY · 世界包 r1")) phase = 4;
            }
            case 4 -> {
                action("upgrade-review", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-generation');const c=p?.querySelector('.world-patch-job[data-state=READY]');const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='审查世界候选源码');if(!b)return false;b.click();return true;");
                if (review.contains("READY · 原版 r1 → 候选 r2")) phase = 5;
            }
            case 5 -> {
                action("upgrade-apply-source", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId?.startsWith('world-review-'));const q=p?.querySelector('[data-world-patch-consent=true]');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='应用已审查的世界版本');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");
                if (BootUpgradeSmokeServer.runtimePackage != null
                        && BootUpgradeSmokeServer.runtimePackage.revision() == 2) phase = 6;
            }
            case 6 -> {
                action("upgrade-reopen-catalog", "document.querySelector('#open-package-catalog')?.click();return true;");
                if (catalog.contains("r2")) phase = 7;
            }
            case 7 -> {
                action("upgrade-reopen-boot", catalogButton("构建 / 安装启动扩展"));
                if (boot.contains("待构建：" + BootUpgradeSmokeServer.NAME + " · r2")
                        && boot.contains("LOADER_CONSTRUCTOR_RETURNED")) phase = 8;
            }
            case 8 -> {
                action("upgrade-build-replacement", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='以此旧构建为前驱编译当前包');if(!b||b.disabled)return false;b.click();return true;");
                if (boot.contains("替换前驱") && boot.contains(" · BUILT · ")) phase = 9;
            }
            case 9 -> {
                action("upgrade-stage-cancelled", stageBuild("cancelled"));
                if (boot.contains(BootUpgradeSmokeServer.MOD_ID + " · WAIT_OFFLINE")
                        && boot.contains("批准文件 true")) phase = 10;
            }
            case 10 -> {
                action("upgrade-cancel", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent.includes('WAIT_OFFLINE')&&[...n.querySelectorAll('button')].some(b=>b.textContent==='取消此计划'));const q=c?.querySelector('input[type=checkbox]');const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='取消此计划');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");
                if (boot.contains(BootUpgradeSmokeServer.MOD_ID + " · CANCELLED")
                        && boot.contains("替换前驱") && boot.contains(" · BUILT · ")) phase = 11;
            }
            case 11 -> {
                action("upgrade-stage-active", stageBuild("active"));
                int waiting = boot.split(BootUpgradeSmokeServer.MOD_ID + " · WAIT_OFFLINE", -1).length - 1;
                if (waiting == 1 && boot.contains(BootUpgradeSmokeServer.MOD_ID + " · CANCELLED")
                        && boot.contains("批准文件 true")) {
                    require(glass, "BOOT_UPGRADE_GLASS");
                    finish("REAL_CEF_BOOT_V2_PATCH_BUILD_CANCEL_RESTAGE_VERIFIED", Map.of(
                            "head", BootUpgradeSmokeServer.runtimePackage,
                            "v1StillLoaded", Files.readString(Minecraft.getInstance().gameDirectory.toPath()
                                    .resolve("boot-upgrade-runtime.txt")).startsWith("V1|"),
                            "controlledProvider", true));
                }
            }
        }
    }

    private static void verifyStage(String boot) throws Exception {
        if (phase == 0) {
            action("observe-" + BootUpgradeSmokeServer.stage(), "document.querySelector('#open-boot-extensions')?.click();return true;");
            phase = 1;
        }
        if (phase != 1 || !BootUpgradeSmokeServer.ready) return;
        boolean applied = BootUpgradeSmokeServer.stage().equals("verify");
        String expected = applied ? "APPLIED" : "ROLLED_BACK";
        if (boot.contains(BootUpgradeSmokeServer.MOD_ID + " · " + expected)
                && boot.contains((applied ? "apply 回执 true" : "rollback 回执 true"))
                && boot.contains("文件 HASH_MATCHED") && boot.contains("LOADER_CONSTRUCTOR_RETURNED")) {
            require(glass, "BOOT_UPGRADE_GLASS");
            String marker = Files.readString(Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("boot-upgrade-runtime.txt"));
            require(marker.startsWith(applied ? "V2|" : "V1|"), "BOOT_UPGRADE_MARKER");
            finish(applied ? "REAL_BOOT_V2_LOADER_SOURCE_APPLIED_VERIFIED"
                            : "REAL_BOOT_V1_OFFLINE_ROLLBACK_LOADER_VERIFIED",
                    Map.of("marker", marker, "head", BootUpgradeSmokeServer.runtimePackage,
                            "loaderConstructorReturned", true, "offlineReceiptObserved", true));
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!BootUpgradeSmokeServer.enabled() || finished) return;
        var mc = Minecraft.getInstance();
        ticks++;
        try {
            if (ticks > 20000) throw new IllegalStateException("BOOT_UPGRADE_CEF_TIMEOUT_"
                    + BootUpgradeSmokeServer.stage() + "_" + phase);
            if (BootUpgradeSmokeServer.failure != null)
                throw new IllegalStateException(BootUpgradeSmokeServer.failure);
            if (!backup && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen) {
                backup = true;
                var field = screen.getClass().getDeclaredField("onProceed");
                field.setAccessible(true);
                ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) field.get(screen))
                        .proceed(false, false);
            }
            if (!trusted && mc.player != null) {
                if (ticks % 20 == 0) net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                        new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());
                if (mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen
                        && !trustPressed) for (var child : screen.children())
                    if (child instanceof net.minecraft.client.gui.components.Button button && button.active
                            && button.getMessage().getString().equals("信任此服务器")) {
                        button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                            public int input() { return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER; }
                            public int modifiers() { return 0; }
                        });
                        trustPressed = true;
                        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                                new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());
                        break;
                    }
                var snapshot = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();
                String fingerprint = snapshot.values().getOrDefault("security.identityFingerprint", "");
                var status = new dev.mineagent.runtime.client.trust.ServerTrustStore(
                        mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"))
                        .status("local-integrated", fingerprint);
                if (status == dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED
                        && (trustPressed || !BootUpgradeSmokeServer.stage().equals("prepare"))) {
                    trusted = true;
                    write("trust-" + BootUpgradeSmokeServer.stage(), Map.of(
                            "actualNativeTrustButton", trustPressed, "storedTrust", !trustPressed,
                            "fingerprint", fingerprint, "systemInputInjected", false));
                    mc.setScreen(null);
                } else return;
            }
            if (!BootUpgradeSmokeServer.ready || BootUpgradeSmokeServer.runtimePackage == null) return;
            var host = WebGuiHostAdapter.INSTANCE;
            if (!opened) { opened = true; host.open(); }
            if (!host.ready() || UiClientSessions.current() == null || ticks % 10 != 0) return;
            poll();
            String catalog = text("catalogText"), boot = text("bootText"), tools = text("toolsText"),
                    generation = text("generationText"), patch = text("patchText"), review = text("reviewText");
            if (ticks % 100 == 0) write("progress-" + BootUpgradeSmokeServer.stage(), Map.of(
                    "stage", BootUpgradeSmokeServer.stage(), "phase", phase, "ticks", ticks,
                    "catalog", catalog, "boot", boot, "tools", tools, "generation", generation,
                    "patch", patch, "review", review, "serverFailure", Objects.toString(BootUpgradeSmokeServer.failure, "")));
            switch (BootUpgradeSmokeServer.stage()) {
                case "prepare" -> prepare(catalog, boot);
                case "upgrade" -> upgrade(catalog, boot, tools, generation, patch, review);
                case "verify", "rollback" -> verifyStage(boot);
                default -> throw new IllegalArgumentException("BOOT_UPGRADE_STAGE");
            }
        } catch (Exception failure) {
            fail(failure);
            throw failure;
        }
    }
}
