package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.client.scripts.LocalClientScriptStore;
import dev.mineagent.runtime.neoforge.ui.ClientStudioSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.*;
import java.util.*;

/** Production CEF manual CLIENT Studio flow. All interaction is DOM JavaScript, never OS input. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class ClientStudioSmokeClient {
    private static final Gson JSON = new Gson();
    private static JsonObject probe;
    private static int ticks, phase;
    private static boolean backup, trusted, trustPressed, opened, finished, managerGlass, studioGlass;

    private ClientStudioSmokeClient() {}

    public static void accept(JsonObject value) {
        if (!ClientStudioSmokeServer.enabled()) return;
        probe = value;
        managerGlass |= text("cardBackground").startsWith("rgba(");
        studioGlass |= text("studioCardBackground").startsWith("rgba(");
    }

    private static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("client-studio-smoke");
    }

    private static void write(String name, Object value) throws Exception {
        Files.createDirectories(root());
        Files.writeString(root().resolve(name + ".json"), JSON.toJson(value));
    }

    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }

    private static void script(String source) {
        var host = WebGuiHostAdapter.INSTANCE;
        host.browser().executeJavaScript("(()=>{" + source + "})();", host.browser().getURL(), 0);
    }

    private static void action(String key, String source) {
        script("window.__clientStudioSmoke??={};if(window.__clientStudioSmoke[" + JSON.toJson(key)
                + "])return;const ok=(()=>{" + source + "})();if(ok)window.__clientStudioSmoke["
                + JSON.toJson(key) + "]=true;");
    }

    private static void poll() {
        script("window.dispatchEvent(new Event('mineagent:client-script-probe'));");
    }

    private static String text(String key) {
        return probe == null || !probe.has(key) ? "" : probe.get(key).getAsString();
    }

    private static LocalClientScriptStore.Asset asset(String name) throws Exception {
        return LocalClientScriptStore.get(Minecraft.getInstance().gameDirectory.toPath()).list().stream()
                .filter(value -> value.manifest().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("CLIENT_STUDIO_ASSET_MISSING_" + name));
    }

    private static Optional<LocalClientScriptStore.Asset> optionalAsset(String name) throws Exception {
        return LocalClientScriptStore.get(Minecraft.getInstance().gameDirectory.toPath()).list().stream()
                .filter(value -> value.manifest().name().equals(name)).findFirst();
    }

    private static void manager(String key, String name, String label) {
        action(key, "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-client-scripts');"
                + "const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent.startsWith("
                + JSON.toJson(name) + "));const q=c?.querySelector('input[type=checkbox]');"
                + "const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==="
                + JSON.toJson(label) + ");if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");
    }

    private static void fail(Throwable error) throws Exception {
        finished = true;
        write("client-failure", Map.of("phase", phase, "ticks", ticks, "error", error.toString(),
                "serverFailure", Objects.toString(ClientStudioSmokeServer.failure, ""),
                "probe", probe == null ? "{}" : probe.toString()));
        WebGuiHostAdapter.INSTANCE.close();
        Minecraft.getInstance().stop();
    }

    private static String rhinoSource() {
        return "var helper=require('client/helper.js');"
                + "client.status('STUDIO_RHINO:'+helper.value);"
                + "track(client.cleanupStatus('STUDIO_RHINO_CLEANED'));helper.value;";
    }

    private static String javaSource() {
        return "package dev.mineagent.studio; import java.util.Map; "
                + "import dev.mineagent.runtime.api.packages.ClientRuntimeHost; "
                + "import dev.mineagent.runtime.scripting.javaext.ClientRuntimeExtension; "
                + "public final class ManualClientExtension implements ClientRuntimeExtension { "
                + "public Object start(Map<String,Object> bindings){var client=(ClientRuntimeHost)bindings.get(\"client\");"
                + "String value=\"STUDIO_JAVA:\"+StudioHelper.value();client.status(value);"
                + "client.cleanupStatus(\"STUDIO_JAVA_CLEANED\");return value;} }";
    }

    private static String createDraftScript(boolean java) {
        String path = java ? "client/dev/mineagent/studio/ManualClientExtension.java" : "client/main.js";
        String name = java ? ClientStudioSmokeServer.JAVA_NAME : ClientStudioSmokeServer.RHINO_NAME;
        String source = java ? javaSource() : rhinoSource();
        return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                + "const path=[...p?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('源码路径'))?.querySelector('input');"
                + "const name=[...p?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('统一包名称'))?.querySelector('input');"
                + "const agent=p?.querySelector('select');const editor=p?.querySelector('textarea[aria-label=\"Java 或 Rhino 源码\"]');"
                + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='创建持久草稿与任务');"
                + "if(!path||!name||!agent||agent.options.length<2||!editor||!b||b.disabled)return false;"
                + "path.value=" + JSON.toJson(path) + ";path.dispatchEvent(new Event('input',{bubbles:true}));"
                + "name.value=" + JSON.toJson(name) + ";name.dispatchEvent(new Event('input',{bubbles:true}));"
                + "agent.value=agent.options[1].value;agent.dispatchEvent(new Event('change',{bubbles:true}));"
                + "editor.value=" + JSON.toJson(source) + ";editor.dispatchEvent(new Event('input',{bubbles:true}));"
                + "b.click();return true;";
    }

    private static String addFileScript(String path, String source) {
        return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                + "const e=p?.querySelector('details.studio-editor-tools');const a=e?.querySelector('textarea[aria-label=\"工作区文件源码\"]');"
                + "const i=[...e?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('文件路径'))?.querySelector('input');"
                + "const b=[...e?.querySelectorAll('button')||[]].find(n=>n.textContent.startsWith('添加文件（'));"
                + "if(!a||!i||!b||b.disabled)return false;i.value=" + JSON.toJson(path)
                + ";i.dispatchEvent(new Event('input',{bubbles:true}));a.value=" + JSON.toJson(source)
                + ";a.dispatchEvent(new Event('input',{bubbles:true}));b.click();return true;";
    }

    private static String dependencyScript() {
        String id = ClientStudioSmokeServer.dependencyPackage.packageId().toString();
        return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                + "const e=p?.querySelector('details.studio-editor-tools');"
                + "const select=[...e?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('选择可归属的准确依赖包与版本'))?.querySelector('select');"
                + "const q=[...e?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('确认添加/更改声明'))?.querySelector('input');"
                + "const b=[...e?.querySelectorAll('button')||[]].find(n=>n.textContent==='保存所选依赖声明');"
                + "if(!select||![...select.options].some(o=>o.value===" + JSON.toJson(id) + ")||!q||!b||b.disabled)return false;select.value=" + JSON.toJson(id)
                + ";select.dispatchEvent(new Event('change',{bubbles:true}));q.checked=true;b.click();return true;";
    }

    private static String publishScript(String name) {
        return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                + "const input=[...p?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('统一包名称'))?.querySelector('input');"
                + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='发布为统一 RuntimePackage 源码');"
                + "if(!input||!b||b.disabled)return false;input.value=" + JSON.toJson(name)
                + ";input.dispatchEvent(new Event('input',{bubbles:true}));b.click();return true;";
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!ClientStudioSmokeServer.enabled() || finished) return;
        var mc = Minecraft.getInstance();
        var host = WebGuiHostAdapter.INSTANCE;
        ticks++;
        try {
            if (ticks > 20000) throw new IllegalStateException("CLIENT_STUDIO_CEF_TIMEOUT_" + phase);
            if (ClientStudioSmokeServer.failure != null)
                throw new IllegalStateException(ClientStudioSmokeServer.failure);
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
                        && !trustPressed) {
                    for (var child : screen.children()) {
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
                    }
                }
                var snapshot = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();
                String fingerprint = snapshot.values().getOrDefault("security.identityFingerprint", "");
                if (trustPressed && new dev.mineagent.runtime.client.trust.ServerTrustStore(
                        mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"))
                        .status("local-integrated", fingerprint)
                        == dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED) {
                    trusted = true;
                    write("trust", Map.of("actualNativeTrustButton", true, "fingerprint", fingerprint,
                            "systemInputInjected", false));
                    mc.setScreen(null);
                } else return;
            }
            if (ClientStudioSmokeServer.dependencyPackage == null) return;
            if (!opened) {
                opened = true;
                host.open();
            }
            if (!host.ready() || UiClientSessions.current() == null || ticks % 10 != 0) return;
            poll();
            String catalog = text("catalogText"), managerText = text("clientScriptText"), studio = text("studioText");
            if (ticks % 100 == 0) write("progress", Map.of(
                    "phase", phase, "ticks", ticks, "catalog", catalog.substring(0, Math.min(2000, catalog.length())),
                    "manager", managerText.substring(0, Math.min(2000, managerText.length())),
                    "studio", studio.substring(0, Math.min(4000, studio.length())),
                    "assets", LocalClientScriptStore.get(mc.gameDirectory.toPath()).list(),
                    "serverFailure", Objects.toString(ClientStudioSmokeServer.failure, "")));
            switch (phase) {
                case 0 -> {
                    action("catalog", "document.querySelector('#open-package-catalog')?.click();return true;");
                    if (catalog.contains(ClientStudioSmokeServer.DEPENDENCY_NAME)) phase = 1;
                }
                case 1 -> {
                    action("dependency-manager", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-catalog');"
                            + "const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="
                            + JSON.toJson(ClientStudioSmokeServer.DEPENDENCY_NAME) + ");"
                            + "const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='本机 CLIENT 原生代码下载 / 执行');"
                            + "if(!b)return false;b.click();return true;");
                    if (managerText.contains("所选服务器包：" + ClientStudioSmokeServer.DEPENDENCY_NAME)) phase = 2;
                }
                case 2 -> {
                    action("dependency-download", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-client-scripts');"
                            + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='下载 CLIENT Rhino（不执行）');"
                            + "if(!b||b.disabled)return false;b.click();return true;");
                    var value = optionalAsset(ClientStudioSmokeServer.DEPENDENCY_NAME);
                    if (value.isPresent() && value.get().state().equals("DOWNLOADED")) {
                        require(!value.get().localConsent() && !ClientScriptPackages.loaded(value.get().filename()),
                                "CLIENT_STUDIO_DEPENDENCY_DOWNLOAD_EXECUTED");
                        phase = 3;
                    }
                }
                case 3 -> {
                    manager("dependency-start", ClientStudioSmokeServer.DEPENDENCY_NAME,
                            "本机编译并启动 CLIENT RHINO");
                    var value = asset(ClientStudioSmokeServer.DEPENDENCY_NAME);
                    if (value.state().equals("RUNNING") && ClientScriptPackages.loaded(value.filename())
                            && ClientScriptPackages.runtimeStatus(value.filename()).equals("STUDIO_DEP_READY")) phase = 4;
                }
                case 4 -> {
                    action("open-studio", "document.querySelector('#open-java-studio')?.click();return true;");
                    if (studio.contains("Code Studio · SERVER / CLIENT")) phase = 5;
                }
                case 5 -> {
                    action("new-rhino", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='新建 CLIENT js');"
                            + "if(!b)return false;b.click();return true;");
                    if (studio.contains("Code Studio · 新草稿") && studio.contains("CLIENT Coder 上下文")) phase = 6;
                }
                case 6 -> {
                    action("create-rhino", createDraftScript(false));
                    if (studio.contains("CLIENT · DRAFT r1")) phase = 7;
                }
                case 7 -> {
                    action("rhino-workspace-open", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const d=p?.querySelector('details.studio-editor-tools');if(!d)return false;if(!d.open)d.querySelector('summary').click();return true;");
                    if (studio.contains("入口 client/main.js")) phase = 8;
                }
                case 8 -> {
                    action("rhino-add-open", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const d=p?.querySelector('details.studio-editor-tools');const b=[...d?.querySelectorAll('button')||[]].find(n=>n.textContent==='添加文件');"
                            + "if(!b||b.disabled)return false;b.click();return true;");
                    if (studio.contains("添加文件（可先保存空白）")) phase = 9;
                }
                case 9 -> {
                    action("rhino-add-save", addFileScript("client/helper.js", "({value:'MULTI'});"));
                    if (studio.contains("工作区") && studio.contains("DRAFT r2")) phase = 10;
                }
                case 10 -> {
                    action("rhino-dependencies-open", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const d=p?.querySelector('details.studio-editor-tools');if(!d)return false;if(!d.open)d.querySelector('summary').click();const b=[...d.querySelectorAll('button')].find(n=>n.textContent==='依赖声明 / 已加载诊断');"
                            + "if(!b||b.disabled)return false;b.click();return true;");
                    if (studio.contains("CLIENT_LOCAL_RUNTIME_REQUIRED")) phase = 11;
                }
                case 11 -> {
                    action("rhino-dependency-save", dependencyScript());
                    if (studio.contains("DRAFT r3") && studio.contains("1 直接依赖")) phase = 12;
                }
                case 12 -> {
                    action("rhino-publish", publishScript(ClientStudioSmokeServer.RHINO_NAME));
                    if (ClientStudioSmokeServer.rhinoPackage != null
                            && studio.contains("下载已发布 CLIENT 源码（不执行）")) phase = 13;
                }
                case 13 -> {
                    action("rhino-download", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='下载已发布 CLIENT 源码（不执行）');"
                            + "if(!b||b.disabled)return false;b.click();return true;");
                    var value = optionalAsset(ClientStudioSmokeServer.RHINO_NAME);
                    if (value.isPresent() && value.get().state().equals("DOWNLOADED")) {
                        require(!value.get().localConsent() && !ClientScriptPackages.loaded(value.get().filename()),
                                "CLIENT_STUDIO_RHINO_DOWNLOAD_EXECUTED");
                        write("rhino-downloaded", Map.of("asset", value.get(), "serverPackage",
                                ClientStudioSmokeServer.rhinoPackage));
                        phase = 14;
                    }
                }
                case 14 -> {
                    manager("rhino-start", ClientStudioSmokeServer.RHINO_NAME,
                            "本机编译并启动 CLIENT RHINO");
                    var value = asset(ClientStudioSmokeServer.RHINO_NAME);
                    if (value.state().equals("RUNNING") && ClientScriptPackages.loaded(value.filename())
                            && ClientScriptPackages.runtimeStatus(value.filename())
                            .equals(ClientStudioSmokeServer.RHINO_STATUS)) phase = 15;
                }
                case 15 -> {
                    manager("rhino-stop", ClientStudioSmokeServer.RHINO_NAME, "停止此本机代码");
                    var value = asset(ClientStudioSmokeServer.RHINO_NAME);
                    if (value.state().equals("STOPPED") && !ClientScriptPackages.loaded(value.filename())
                            && ClientScriptPackages.runtimeStatus(value.filename()).equals("STUDIO_RHINO_CLEANED"))
                        phase = 16;
                }
                case 16 -> {
                    action("studio-list", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent.startsWith('草稿列表'));if(!b)return false;b.click();return true;");
                    if (studio.contains("Code Studio · SERVER / CLIENT")) phase = 17;
                }
                case 17 -> {
                    action("new-java", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');"
                            + "const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='新建 CLIENT java');if(!b)return false;b.click();return true;");
                    if (studio.contains("Code Studio · 新草稿") && studio.contains("CLIENT Coder 上下文")) phase = 18;
                }
                case 18 -> {
                    action("create-java", createDraftScript(true));
                    if (studio.contains("CLIENT · DRAFT r1")) phase = 19;
                }
                case 19 -> {
                    action("java-workspace-open", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');const d=p?.querySelector('details.studio-editor-tools');if(!d)return false;if(!d.open)d.querySelector('summary').click();return true;");
                    if (studio.contains("入口 client/dev/mineagent/studio/ManualClientExtension.java")) phase = 20;
                }
                case 20 -> {
                    action("java-add-open", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');const d=p?.querySelector('details.studio-editor-tools');const b=[...d?.querySelectorAll('button')||[]].find(n=>n.textContent==='添加文件');if(!b||b.disabled)return false;b.click();return true;");
                    if (studio.contains("添加文件（可先保存空白）")) phase = 21;
                }
                case 21 -> {
                    action("java-add-save", addFileScript("client/dev/mineagent/studio/StudioHelper.java",
                            "package dev.mineagent.studio; public final class StudioHelper { public static String value(){ return \"MULTI\"; } }"));
                    if (studio.contains("工作区") && studio.contains("DRAFT r2")) phase = 22;
                }
                case 22 -> {
                    action("java-dependencies-open", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');const d=p?.querySelector('details.studio-editor-tools');if(!d)return false;if(!d.open)d.querySelector('summary').click();const b=[...d.querySelectorAll('button')].find(n=>n.textContent==='依赖声明 / 已加载诊断');if(!b||b.disabled)return false;b.click();return true;");
                    if (studio.contains("CLIENT_LOCAL_RUNTIME_REQUIRED")) phase = 23;
                }
                case 23 -> {
                    action("java-dependency-save", dependencyScript());
                    if (studio.contains("DRAFT r3") && studio.contains("1 直接依赖")) phase = 24;
                }
                case 24 -> {
                    action("java-publish", publishScript(ClientStudioSmokeServer.JAVA_NAME));
                    if (ClientStudioSmokeServer.javaPackage != null
                            && studio.contains("下载已发布 CLIENT 源码（不执行）")) phase = 25;
                }
                case 25 -> {
                    action("java-download", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-java-studio');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='下载已发布 CLIENT 源码（不执行）');if(!b||b.disabled)return false;b.click();return true;");
                    var value = optionalAsset(ClientStudioSmokeServer.JAVA_NAME);
                    if (value.isPresent() && value.get().state().equals("DOWNLOADED")) {
                        require(!value.get().localConsent() && !ClientScriptPackages.loaded(value.get().filename()),
                                "CLIENT_STUDIO_JAVA_DOWNLOAD_EXECUTED");
                        write("java-downloaded", Map.of("asset", value.get(), "serverPackage",
                                ClientStudioSmokeServer.javaPackage));
                        phase = 26;
                    }
                }
                case 26 -> {
                    manager("java-start", ClientStudioSmokeServer.JAVA_NAME,
                            "本机编译并启动 CLIENT JAVA");
                    var value = asset(ClientStudioSmokeServer.JAVA_NAME);
                    if (value.state().equals("RUNNING") && ClientScriptPackages.loaded(value.filename())
                            && ClientScriptPackages.runtimeStatus(value.filename())
                            .equals(ClientStudioSmokeServer.JAVA_STATUS)) phase = 27;
                }
                case 27 -> {
                    manager("java-stop", ClientStudioSmokeServer.JAVA_NAME, "停止此本机代码");
                    var value = asset(ClientStudioSmokeServer.JAVA_NAME);
                    if (value.state().equals("STOPPED") && !ClientScriptPackages.loaded(value.filename())
                            && ClientScriptPackages.runtimeStatus(value.filename()).equals("STUDIO_JAVA_CLEANED"))
                        phase = 28;
                }
                case 28 -> {
                    manager("dependency-stop", ClientStudioSmokeServer.DEPENDENCY_NAME, "停止此本机代码");
                    var value = asset(ClientStudioSmokeServer.DEPENDENCY_NAME);
                    if (value.state().equals("STOPPED") && !ClientScriptPackages.loaded(value.filename())
                            && ClientScriptPackages.runtimeStatus(value.filename()).equals("STUDIO_DEP_CLEANED"))
                        phase = 29;
                }
                case 29 -> {
                    if (!ClientStudioSmokeServer.verified) return;
                    action("reopen-catalog", "document.querySelector('#open-package-catalog')?.click();return true;");
                    if (catalog.contains(ClientStudioSmokeServer.RHINO_NAME)
                            && catalog.contains(ClientStudioSmokeServer.JAVA_NAME)) phase = 30;
                }
                case 30 -> {
                    action("reopen-rhino-source", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-catalog');"
                            + "const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="
                            + JSON.toJson(ClientStudioSmokeServer.RHINO_NAME) + ");const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='Code Studio · CLIENT Rhino');if(!b)return false;b.click();return true;");
                    if (text("studioPath").equals("client/main.js")
                            && text("studioSource").equals(rhinoSource())) phase = 31;
                }
                case 31 -> {
                    action("reopen-java-source", "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-catalog');"
                            + "const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="
                            + JSON.toJson(ClientStudioSmokeServer.JAVA_NAME) + ");const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='Code Studio · CLIENT Java');if(!b)return false;b.click();return true;");
                    if (text("studioPath").equals("client/dev/mineagent/studio/ManualClientExtension.java")
                            && text("studioSource").equals(javaSource())) phase = 32;
                }
                case 32 -> {
                    var rhino = asset(ClientStudioSmokeServer.RHINO_NAME);
                    var java = asset(ClientStudioSmokeServer.JAVA_NAME);
                    require(managerGlass && studioGlass, "CLIENT_STUDIO_GLASS_STYLE");
                    require(ClientScriptPackages.build(java.filename()).containsKey("artifact"),
                            "CLIENT_STUDIO_JAVA_ARTIFACT");
                    write("result", Map.ofEntries(
                            Map.entry("status", "REAL_CEF_MANUAL_CLIENT_STUDIO_RHINO_JAVA_VERIFIED"),
                            Map.entry("rhino", rhino), Map.entry("java", java),
                            Map.entry("dependency", asset(ClientStudioSmokeServer.DEPENDENCY_NAME)),
                            Map.entry("rhinoStatus", ClientScriptPackages.runtimeStatus(rhino.filename())),
                            Map.entry("javaStatus", ClientScriptPackages.runtimeStatus(java.filename())),
                            Map.entry("javaBuild", ClientScriptPackages.build(java.filename())),
                            Map.entry("multiFile", true), Map.entry("dependenciesPreserved", true),
                            Map.entry("existingSourcesReloadedExactly", true),
                            Map.entry("downloadExecuted", false), Map.entry("serverCodeExecuted", false),
                            Map.entry("semiTransparent", true), Map.entry("providerCalls", 0),
                            Map.entry("systemInputInjected", false), Map.entry("fullV1", false)));
                    finished = true;
                    dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                            "MINEAGENT_CLIENT_STUDIO_OK");
                    host.close();
                    mc.stop();
                }
            }
        } catch (Exception error) {
            fail(error);
            throw error;
        }
    }
}
