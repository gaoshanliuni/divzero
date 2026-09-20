package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.client.webui.WebGuiTheme;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit local visual fixture in the real pinned CEF; no Provider, business session or production package. */
final class ThemeSmokeClient {
    private static final Gson JSON = new Gson();
    private static final String RUN = UUID.randomUUID().toString();
    private static int ticks, phase, next;
    private static boolean busy, ended;
    private static String view, failure;
    private ThemeSmokeClient() {}

    static void tick() {
        if (ended) return;
        var mc = Minecraft.getInstance(); var host = WebGuiHostAdapter.INSTANCE; ticks++;
        try {
            if (ticks == 1) Files.createDirectories(root());
            if (phase == 0 && host.ready()) {
                byte[] html = Files.readAllBytes(Path.of(System.getProperty("mineagent.themeFixture")));
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html));
                var ref = new RuntimeResourceRef("ui/index.html", hash, RuntimeResourceSide.CLIENT, "text/html", html.length);
                var pkg = new RuntimePackage(UUID.randomUUID(), RuntimePackageType.CONTENT, "主题状态 · 本地回归夹具", "1.0.0",
                        ActivationMode.HOT_RUNTIME, Map.of(), Set.of(), Map.of("ui", new RuntimeEntrypoint(ref.path(), ref.side(), hash)),
                        Map.of(), Map.of(ref.path(), ref), PackageOrigin.LOCAL_STUDIO, false, 1, hash, "LOCAL_VISUAL_FIXTURE_NOT_PUBLISHED", 0);
                view = host.openPackagePreview(pkg, ignored -> html, ref.path(), false);
                Files.writeString(root().resolve("fixture.json"), JSON.toJson(Map.of("sha256", hash, "source", "test-fixtures/webgui/theme-controls.html",
                        "providerCalls", 0, "businessVerified", false, "run", RUN)));
                main("const n=document.querySelector('[data-view-id=\"" + view + "\"]');Object.assign(n.style,{left:'30px',top:'100px',width:Math.min(900,innerWidth-60)+'px',height:(innerHeight-140)+'px'});");
                phase = 1; next = ticks + 30;
            }
            if (phase == 1 && ticks >= next && host.packageLoaded(view)) {
                // Verify repeat application does not dispatch handlers or change the draft.
                frame(WebGuiTheme.packageScript() + "(" + JSON.toJson(WebGuiTheme.packageCss()) + ");window.probeTheme();");
                phase = 2; next = ticks + 20;
            }
            if (phase == 2 && ticks >= next && !busy) {
                busy = true;
                PackagePageAgent.inspectManagedView(view).whenComplete((observation, error) -> mc.execute(() -> {
                    try {
                        if (error != null) throw new IllegalStateException(error);
                        Files.writeString(root().resolve("observation.json"), observation);
                        String text = JsonParser.parseString(observation).getAsJsonObject().get("visibleText").getAsString();
                        int marker = text.indexOf("THEME_PROOF:");
                        if (marker < 0) throw new IllegalStateException("THEME_PROOF_MISSING");
                        var proof = JsonParser.parseString(text.substring(marker + "THEME_PROOF:".length())).getAsJsonObject();
                        proof.addProperty("tint", Integer.toHexString(WebGuiTheme.tint(true)));
                        Files.writeString(root().resolve("proof.json"), JSON.toJson(proof));
                        var failed = proof.getAsJsonObject("checks").entrySet().stream().filter(e -> !e.getValue().getAsBoolean()).map(Map.Entry::getKey).toList();
                        if (!failed.isEmpty()) failure = "THEME_CHECKS_FAILED: " + String.join(",", failed);
                        // Remove diagnostics from the screenshot, not from the saved actual observation.
                        frame("document.querySelector('#theme-proof').textContent='';");
                        phase = 3; next = ticks + 30; busy = false;
                    } catch (Exception e) { fail(e); }
                }));
            }
            if (phase == 3 && ticks >= next && !busy) {
                busy = true;
                PackagePageAgent.captureManagedView(view).whenComplete((shot, error) -> mc.execute(() -> {
                    try {
                        if (error != null) throw new IllegalStateException(error);
                        Files.write(root().resolve("private.png"), shot.png());
                        phase = 4; next = ticks + 10; busy = false;
                    } catch (Exception e) { fail(e); }
                }));
            }
            if (phase == 4 && ticks >= next && !busy) {
                busy = true;
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                    try (image) {
                        image.writeToFile(root().resolve("menu.png"));
                        mc.execute(() -> { phase = 5; busy = false; });
                    } catch (Exception e) { mc.execute(() -> fail(e)); }
                });
            }
            if (phase == 5) {
                Files.writeString(root().resolve("result.json"), JSON.toJson(Map.of("status", failure == null ? "THEME_CONTROLS_VERIFIED" : failure,
                        "providerCalls", 0, "mode", "REAL_CEF_LOCAL_VISUAL_FIXTURE", "fullV1", false)));
                ended = true; host.close(); mc.stop();
                if (failure != null) throw new IllegalStateException(failure);
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_THEME_CONTROLS_OK run={}", RUN);
            }
            if (ticks > 1800) throw new IllegalStateException("THEME_SMOKE_TIMEOUT phase=" + phase);
        } catch (Exception e) { fail(e); }
    }
    private static void fail(Exception e) {
        ended = true;
        try { Files.writeString(root().resolve("failure.json"), JSON.toJson(Map.of("phase", phase, "error", e.toString()))); }
        catch (Exception write) { e.addSuppressed(write); }
        WebGuiHostAdapter.INSTANCE.close(); Minecraft.getInstance().stop();
        throw new IllegalStateException("THEME_SMOKE_FAILED", e);
    }
    private static Path root() { return Minecraft.getInstance().gameDirectory.toPath().resolve("theme-evidence").resolve(RUN); }
    private static void main(String script) { var b = WebGuiHostAdapter.INSTANCE.browser(); b.executeJavaScript("(()=>{" + script + "})();", b.getURL(), 0); }
    private static void frame(String script) {
        var h = WebGuiHostAdapter.INSTANCE; String url = h.packageUrl(view);
        for (long id : h.browser().getFrameIdentifiers()) {
            var f = h.browser().getFrame(id);
            if (f != null && !f.isMain() && url.equals(f.getURL())) { f.executeJavaScript(script, url, 0); return; }
        }
        throw new IllegalStateException("THEME_FRAME_MISSING");
    }
}
