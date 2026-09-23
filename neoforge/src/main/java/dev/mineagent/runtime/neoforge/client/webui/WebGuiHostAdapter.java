package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.client.webui.LocalUiResourceServer;
import dev.mineagent.runtime.client.webui.PackageUiResolver;
import dev.mineagent.runtime.client.webui.WebGuiMessageGate;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import land.webgui.WebHudOverlay;
import land.webgui.WebSession;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Physical-client only. Upstream WebviewApi broadcasts, so private events use this targeted router. */
public final class WebGuiHostAdapter implements AutoCloseable {
    public static final WebGuiHostAdapter INSTANCE = new WebGuiHostAdapter();
    private static final Gson JSON = new Gson();
    private static volatile boolean originIsolation;
    private final WebGuiMessageGate gate = new WebGuiMessageGate();
    private final Map<String, LocalUiResourceServer.Mount> packageMounts = new LinkedHashMap<>();
    private final Map<String, String> packageUrls = new LinkedHashMap<>();
    public record ViewPackage(UUID packageId,long revision,String entry,boolean passive){}
    private final Map<String,ViewPackage> viewPackages=new LinkedHashMap<>();
    private final Map<String,dev.mineagent.runtime.core.ui.UiViewSettings.Settings> viewSettings=new LinkedHashMap<>();
    private final Map<String,JsonObject> viewLayouts=new LinkedHashMap<>();
    private final java.util.Set<String> hiddenPackages=new java.util.HashSet<>();
    private final java.util.Set<String> loadedPackages = new java.util.HashSet<>();
    private LocalUiResourceServer resources;
    private LocalUiResourceServer.Mount shell;
    private MCEFBrowser browser;
    private CefMessageRouter router;
    private boolean ready;
    private boolean backgroundOpen;
    private boolean standaloneOpen;
    private boolean workspaceVisible,compositionActive;
    private long openNanos;
    private long lastSnapshotNanos;
    private long readyMillis;
    private Object connection;
    private String diagnostic = "VIEW_NOT_RENDERED";
    private long rateWindow;
    private int rateCount;
    private WebGuiHostAdapter() {}

    public String diagnostic() { return diagnostic; }
    public boolean compositionActive(){return compositionActive;}
    public boolean workspaceShown(){return dev.mineagent.runtime.client.webui.WorkspaceShortcut.shown(workspaceVisible,Minecraft.getInstance().screen instanceof WebGuiInteractionScreen,Minecraft.getInstance().screen instanceof WebGuiDiagnosticScreen);}
    public boolean ready() { return ready && browser != null && gate.owns(browser); }
    public MCEFBrowser browser() { return browser; }
    public String packageUrl(String viewId) { return packageUrls.get(viewId); }
    public String viewForPackageUrl(String url){return packageUrls.entrySet().stream().filter(e->e.getValue().equals(url)).map(Map.Entry::getKey).findFirst().orElse(null);}
    public boolean packageLoaded(String viewId) { return loadedPackages.contains(viewId); }
    public java.util.List<String> loadedPreviewViews(){return loadedPackages.stream().filter(view->!PackageContentClient.owns(view)).sorted().toList();}
    public ViewPackage viewPackage(String view){return viewPackages.get(view);}
    public JsonObject viewLayout(String view){var value=viewLayouts.get(view);return value==null?null:value.deepCopy();}
    public boolean packageHidden(String view){return hiddenPackages.contains(view);}
    public long readyMillis() { return readyMillis; }
    public boolean owns(CefBrowser candidate) { return gate.owns(candidate); }
    public boolean acceptsTrustedFrame(CefBrowser browser,CefFrame frame,int size){return frame!=null&&gate.accepts(browser,frame.isMain(),frame.getURL(),size);}

    public static void recordStartupFlags(String[] flags) {
        originIsolation = flags != null && java.util.Arrays.stream(flags)
                .noneMatch(flag -> flag.equals("--disable-web-security") || flag.startsWith("--disable-web-security="));
    }

    public void open() { if(browser==null)standaloneOpen=false;workspaceVisible=true;open(true); }
    public void toggleWorkspace(){requireClientThread();if(workspaceShown())hideWorkspace();else{workspaceVisible=true;if(browser==null)standaloneOpen=false;open(true,false);}}
    public void hideWorkspace(){requireClientThread();workspaceVisible=false;backgroundOpen=true;emit("workspaceMode",Map.of("visible",false));var mc=Minecraft.getInstance();if(mc.screen instanceof WebGuiInteractionScreen||mc.screen instanceof WebGuiDiagnosticScreen)mc.setScreen(null);}
    void cancelPendingStandalone(){requireClientThread();if(browser==null)standaloneOpen=false;}
    public void openPassive() { open(false); }
    public void openStandalone(){requireClientThread();if(browser==null)standaloneOpen=true;if(!(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen))workspaceVisible=false;open(false);if(browser!=null){backgroundOpen=false;if(!(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen))Minecraft.getInstance().setScreen(new WebGuiInteractionScreen());}}
    public void revealWorldView(String view){requireClientThread();openStandalone();if(ready())emit("revealPackage",Map.of("viewId",view));}
    private void open(boolean interactive) {
        open(interactive,true);
    }
    private void open(boolean interactive,boolean revealChat) {
        requireClientThread();
        Minecraft mc = Minecraft.getInstance();
        if(interactive)backgroundOpen=false;
        if (!MCEF.isInitialized()) { diagnostic = "BROWSER_NOT_READY: MCEF 尚未完成准备"; if(interactive)showDiagnostic(); return; }
        if (!originIsolation) {
            diagnostic = "WEB_SECURITY_DISABLED: 设置 cef-disable-web-security=false 后重启游戏";
            if(interactive)showDiagnostic(); return;
        }
        try {
            ensureRouter();WebGuiPopupCompositor.register();
            if (browser != null && WebSession.hudBrowser() != browser) close();
            if (browser == null) {
                WebGuiAtlasCompositor.configure(mc.gameDirectory.toPath().resolve("config/mineagent-webgui.properties"));
                backgroundOpen=!interactive;
                if (WebSession.browser() != null || WebSession.hudBrowser() != null) {
                    diagnostic = "HOST_BUSY: 其他 WebGUI 页面正在占用上游宿主"; if(interactive)showDiagnostic(); return;
                }
                resources = new LocalUiResourceServer(13, 32L * 1024 * 1024);
                var assets = new LinkedHashMap<String, PackageUiResolver.Asset>();
                for (String name : dev.mineagent.runtime.client.webui.ShellAssetCatalog.NAMES) {
                    try (var in = getClass().getResourceAsStream("/assets/mineagent_runtime/webui/" + name)) {
                        if (in == null) throw new IOException("Missing bundled UI resource: " + name);
                        byte[] bytes=in.readAllBytes();if(name.equals("index.html"))bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("<body>","<body data-workspace-visible=\""+workspaceVisible+"\""+(standaloneOpen?" data-standalone=\"true\"":"")+">").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        assets.put(name, new PackageUiResolver.Asset(bytes, name.endsWith("html")
                                ? "text/html" : name.endsWith("css") ? "text/css" : "text/javascript"));
                    }
                }
                shell = resources.mount(assets, true);
                connection = mc.getConnection();
                String url = shell.entry("index.html").toString();
                WebHudOverlay.applyServerOpen(mc, url);
                browser = WebSession.hudBrowser();
                browser.useBrowserControls(false);
                gate.bind(browser, url);
                ready = false;
                openNanos = System.nanoTime();
                diagnostic = "PAGE_LOADING";
            } else if (ready) { if(interactive&&revealChat)emit("openChat", Map.of()); UiClientSessions.open(); }
            if(interactive){if(!(mc.screen instanceof WebGuiInteractionScreen))mc.setScreen(new WebGuiInteractionScreen());else interactionMode(true);}
        } catch (Exception failure) {
            close();
            diagnostic = "BROWSER_HOST_FAILURE: " + failure.getClass().getSimpleName();
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("WebGUI host failed", failure);
            if(interactive)showDiagnostic();
        }
    }

    /** The caller must hold client package authorization. Initial slice exposes preview/HUD only, never writes. */
    public String openPackagePreview(RuntimePackage runtimePackage, PackageUiResolver.ContentReader reader,
                                     String entry, boolean passive) throws IOException {
        requireClientThread();
        if (!ready()) throw new IllegalStateException("VIEW_NOT_RENDERED");
        if (!entry.startsWith("ui/") || !entry.endsWith(".html")) throw new IllegalArgumentException("UI_ENTRYPOINT");
        var assets = PackageUiResolver.resolve(runtimePackage.resources(), reader, 8L * 1024 * 1024);
        if (!assets.containsKey(entry)) throw new IllegalArgumentException("UI_ENTRYPOINT_MISSING");
        return openResolvedPreview(new dev.mineagent.runtime.client.webui.PackagePreviewTransfer.Resolved(runtimePackage, entry, assets), passive);
    }
    /** Called only after signature/hash verification (network) or explicitly authorized local preview resolution. */
    public String openResolvedPreview(dev.mineagent.runtime.client.webui.PackagePreviewTransfer.Resolved resolved, boolean passive) {
        return openResolved(resolved,passive,null);
    }
    public String openResolvedContent(dev.mineagent.runtime.client.webui.PackagePreviewTransfer.Resolved resolved, dev.mineagent.runtime.api.ui.UiProtocol.Session session) {
        return openResolvedContent(resolved,session,false);
    }
    public String openResolvedContent(dev.mineagent.runtime.client.webui.PackagePreviewTransfer.Resolved resolved, dev.mineagent.runtime.api.ui.UiProtocol.Session session,boolean passive) {
        if(!session.binding().entryPath().equals(resolved.entry())||!session.binding().ownerPackageId().equals(resolved.runtimePackage().packageId())
                ||session.binding().packageRevision()!=resolved.runtimePackage().revision())throw new IllegalArgumentException("CONTENT_SESSION_CONTEXT");
        if(passive&&!dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(session.binding())&&(session.binding().preview()||session.binding().actorKind()!=dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.PLAYER
                ||!session.binding().capabilities().equals(Set.of("scoreview.read"))
                ||dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(resolved.runtimePackage().entrypoints(),true).filter(resolved.entry()::equals).isEmpty()))
            throw new SecurityException("HUD_SESSION_CONTEXT");
        return openResolved(resolved,passive,session);
    }
    private String openResolved(dev.mineagent.runtime.client.webui.PackagePreviewTransfer.Resolved resolved,boolean passive,dev.mineagent.runtime.api.ui.UiProtocol.Session session) {
        requireClientThread();
        if (!ready()) throw new IllegalStateException("VIEW_NOT_RENDERED");
        var runtimePackage = resolved.runtimePackage();
        String entry = resolved.entry();
        var settingsAsset=resolved.assets().get(dev.mineagent.runtime.core.ui.UiViewSettings.PATH);
        if(settingsAsset!=null){var ref=runtimePackage.resources().get(dev.mineagent.runtime.core.ui.UiViewSettings.PATH);if(ref==null||ref.side()!=dev.mineagent.runtime.api.packages.RuntimeResourceSide.CLIENT||!settingsAsset.mediaType().equals("application/json"))throw new IllegalArgumentException("UI_VIEW_SETTINGS_RESOURCE");}
        var defaults=settingsAsset==null?null:dev.mineagent.runtime.core.ui.UiViewSettings.parse(new String(settingsAsset.bytes(),java.nio.charset.StandardCharsets.UTF_8),runtimePackage.entrypoints().values().stream().filter(e->e.side()==dev.mineagent.runtime.api.packages.RuntimeResourceSide.CLIENT&&e.path().endsWith(".html")).map(e->e.path()).collect(java.util.stream.Collectors.toSet())).get(entry);
        if(defaults!=null&&defaults.opacity()!=null&&!WebGuiAtlasCompositor.automatic()&&!WebGuiAtlasCompositor.inputAvailable())throw new IllegalStateException("UI_OPACITY_BACKEND_REQUIRED");
        var mount = resources.mount(resolved.assets(), false);
        String view = session==null?UUID.randomUUID().toString():session.binding().viewId();
        packageMounts.put(view, mount);
        packageUrls.put(view, mount.entry(entry).toString());
        viewPackages.put(view,new ViewPackage(runtimePackage.packageId(),runtimePackage.revision(),entry,passive));
        if(defaults!=null)viewSettings.put(view,defaults);
        if(session!=null)PackageContentClient.mount(session,mount.entry(entry).toString());
        if(!passive&&!(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen)){hiddenPackages.add(view);PackageContentClient.visibility(view,false);}
        var message=new LinkedHashMap<String,Object>(Map.of("viewId", view, "title", runtimePackage.name() + " · " + runtimePackage.version()+" · r"+runtimePackage.revision(),
                "url", mount.entry(entry).toString(), "mode", passive?"PASSIVE_HUD":session!=null?"CONTENT":"PREVIEW",
                "targetObjectId",session==null?"":session.binding().targetObjectId(),"packageId",runtimePackage.packageId(),"packageRevision",runtimePackage.revision(),"candidatePreview",session!=null&&session.binding().preview(),"contentKind",session!=null&&dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(session.binding())?"DELIVERY":session!=null&&dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(session.binding())?"WORLD":session!=null&&dev.mineagent.runtime.api.ui.ContainerProtocol.bound(session.binding())?"CONTAINER":"SCORE","actorKind",session==null?"":session.binding().actorKind().name()));
        message.put("layoutKey",dev.mineagent.runtime.core.ui.UiViewSettings.layoutKey(runtimePackage.packageId(),entry,session==null?"":dev.mineagent.runtime.api.ui.WorldUiProtocol.localStateTarget(session.binding()),session!=null&&session.binding().preview()));
        if(defaults!=null)message.put("placement",defaults);emit("openPackage",message);
        return view;
    }

    private void ensureRouter() {
        PackagePageAgent.register();
        PackageContentClient.register();
        PackageFormDrafts.register();
        PackageUiStateClient.register();
        WebGuiNativeInput.register();
        PackageViewCapture.register();
        if (router != null) return;
        WebGuiPaintComposition.register();
        router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentQuery", "mineagentQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override public boolean onQuery(CefBrowser source, CefFrame frame, long queryId, String request,
                                             boolean persistent, CefQueryCallback callback) {
                if (frame == null || request == null || persistent
                        || !gate.accepts(source, frame.isMain(), frame.getURL(), request.length()) || !allowMessage()) {
                    callback.failure(403, "UI_SOURCE_REJECTED"); return true;
                }
                String url = frame.getURL();
                Minecraft.getInstance().execute(() -> {
                    if (!gate.accepts(source, true, url, request.length()) || connection != Minecraft.getInstance().getConnection()
                            || !url.equals(source.getURL())) {
                        callback.failure(409, "STALE_VIEW"); return;
                    }
                    try {
                        JsonObject message = JsonParser.parseString(request).getAsJsonObject();
                        String channel = text(message, "channel", 64);
                        if (dev.mineagent.runtime.client.webui.UiHostChannels.remote(channel)) {
                            java.util.concurrent.CompletableFuture<?> operation = channel.equals("persistUiState")
                                    ? UiClientSessions.persist(message.getAsJsonObject("state").toString()) : channel.equals("rendererSettings")?RendererSettingsClient.handle(message):handleRemote(message);
                            operation.whenComplete((receipt, failure) -> Minecraft.getInstance().execute(() -> {
                                if (!gate.accepts(source, true, url, request.length()) || !url.equals(source.getURL())) { callback.failure(409, "STALE_VIEW"); return; }
                                if (failure != null) callback.failure(400, "UI_SERVER_OPERATION_FAILED");
                                else callback.success(JSON.toJson(receipt));
                            }));
                        } else callback.success(JSON.toJson(handle(message)));
                    }
                    catch (Exception failure) { callback.failure(400, "UI_REQUEST_REJECTED: " + failure.getClass().getSimpleName()); }
                });
                return true;
            }
        }, true);
        MCEF.getClient().getHandle().addMessageRouter(router);
    }

    private synchronized boolean allowMessage() {
        long now = System.nanoTime();
        if (now - rateWindow > 1_000_000_000L) { rateWindow = now; rateCount = 0; }
        return ++rateCount <= 60;
    }

    private Map<String, ?> handle(JsonObject message) {
        String channel = text(message, "channel", 64);
        switch (channel) {
            case "nativeAtlasResize" -> {return WebGuiAtlasCompositor.resize(message);}
            case "ready" -> {
                if (message.get("bridgeVersion").getAsInt() != 1) throw new IllegalArgumentException("BRIDGE_VERSION");
                RendererSettingsClient.clear();WebGuiPaintComposition.bindDocument(UUID.fromString(text(message,"hostDocumentId",128)));
                ready = true; diagnostic = "READY"; readyMillis = (System.nanoTime() - openNanos) / 1_000_000;
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WEBGUI_READY elapsedMs={}", readyMillis);
                if(WebGuiAtlasCompositor.automatic())emit("nativeAtlasStart",Map.of("mode","configured","physicalWidth",Minecraft.getInstance().getWindow().getWidth(),"physicalHeight",Minecraft.getInstance().getWindow().getHeight()));
                snapshot();
                interactionMode(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen);
                UiClientSessions.open();
                dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.refreshWebNotice();
            }
            case "echo" -> { return Map.of("text", text(message, "text", 4096), "executionMode", "HOST_ECHO_NOT_BUSINESS"); }
            case "compositionState" -> {compositionActive=message.get("active").getAsBoolean();}
            case "releaseInput" -> {
                if (Minecraft.getInstance().screen instanceof WebGuiInteractionScreen screen) screen.onClose();
            }
            case "openSetup" -> {
                var mc = Minecraft.getInstance();
                mc.setScreen(dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen.create(mc.screen,
                        dev.mineagent.runtime.api.config.PanelSection.PERMISSIONS));
            }
            case "viewClosed" -> {
                String viewId=text(message,"viewId",64);
                HudPersistenceClient.closed(viewId);
                UiClientSessions.decisionWatch(viewId,true);
                PackageUiStateClient.invalidate(viewId);
                PageControlClient.closed(viewId);
                PackageContentClient.close(viewId);
                PackagePageAgent.cancel(viewId); packageUrls.remove(viewId); loadedPackages.remove(viewId);viewPackages.remove(viewId);hiddenPackages.remove(viewId);viewSettings.remove(viewId);viewLayouts.remove(viewId);
                var mount = packageMounts.remove(viewId);
                if (mount != null) mount.close();
            }
            case "previewStop" -> {String view=text(message,"viewId",64);UiAgentClient.stop(view,true,"PLAYER_STOP");PackagePageAgent.cancel(view);}
            case "conversationSpeech" -> {
                var shell=UiClientSessions.current();var mc=Minecraft.getInstance();if(shell==null||!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("NATIVE_SCREEN_BUSY");
                mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.NativeSpeechScreen(mc.screen,shell.binding().worldId(),UUID.fromString(text(message,"agentId",36)),UUID.fromString(text(message,"conversationId",36)),UUID.fromString(text(message,"contextId",36))));
            }
            case "conversationAudioContext" -> {
                var shell=UiClientSessions.current();if(shell==null)throw new IllegalStateException("VIEW_NOT_RENDERED");
                if(message.has("clear")&&message.get("clear").getAsBoolean())dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.clear(message.has("contextId")?UUID.fromString(text(message,"contextId",36)):null);
                else dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.focus(shell.binding().worldId(),UUID.fromString(text(message,"agentId",36)),UUID.fromString(text(message,"conversationId",36)),UUID.fromString(text(message,"contextId",36)));
            }
            case "conversationAudioControl" -> {
                var shell=UiClientSessions.current();if(shell==null)throw new IllegalStateException("VIEW_NOT_RENDERED");
                if("stop".equals(text(message,"action",16))&&message.has("operationIds")){
                    var ids=message.getAsJsonArray("operationIds");if(ids.size()>32)throw new IllegalArgumentException("AUDIO_STOP_BUDGET");var operations=new java.util.ArrayList<UUID>();for(var id:ids)operations.add(UUID.fromString(id.getAsString()));
                    return dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.stopOperations(UUID.fromString(text(message,"contextId",36)),operations);
                }
                return dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.control(UUID.fromString(text(message,"contextId",36)),text(message,"action",16),message.has("operationId")?UUID.fromString(text(message,"operationId",36)):null);
            }
            case "viewVisibility" -> {String view=text(message,"viewId",128);UiClientSessions.decisionWatch(view,false);boolean visible=message.get("visible").getAsBoolean()&&(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen||(viewPackages.containsKey(view)&&viewPackages.get(view).passive()));if(visible)hiddenPackages.remove(view);else hiddenPackages.add(view);PackageContentClient.visibility(view,visible);if(!visible)PackagePageAgent.cancel(view);}
            case "scoreViewPage" -> UiClientSessions.viewPage(message.get("page").getAsInt());
            case "hudRetry" -> HudPersistenceClient.retry(text(message,"key",512));
            case "worldContentProbe" -> {if(Boolean.getBoolean("mineagent.worldContentSmoke"))WorldContentSmokeClient.accept(message);}
            case "worldRestoreProbe" -> {if(Boolean.getBoolean("mineagent.worldRestoreSmoke"))WorldRestoreSmokeClient.accept(message);}
            case "worldActionProbe" -> {if(Boolean.getBoolean("mineagent.worldActionSmoke"))WorldActionSmokeClient.accept(message);}
            case "worldPackageTaskProbe" -> {if(Boolean.getBoolean("mineagent.worldPackageTaskSmoke"))WorldPackageTaskSmokeClient.accept(message);}
            case "generationRepairProbe" -> {if(Boolean.getBoolean("mineagent.generationRepairSmoke"))GenerationRepairSmokeClient.accept(message);}
            case "sharedAgentSmokeProbe" -> {if(Boolean.getBoolean("mineagent.sharedAgentSmoke"))SharedAgentSmokeClient.accept(message);}
            case "scheduleSmokeProbe" -> {if(Boolean.getBoolean("mineagent.scheduleSmoke"))ScheduleSmokeClient.accept(message);}
            case "eventSmokeProbe" -> {if(Boolean.getBoolean("mineagent.eventSmoke"))EventSmokeClient.accept(message);}
            case "worldUiSmokeProbe" -> {if(Boolean.getBoolean("mineagent.worldUiSmoke")){ViewSettingsSmokeClient.paintProbe(message);WorldUiSmokeClient.accept(message);if(NativeAtlasInputSmokeClient.enabled())NativeAtlasInputSmokeClient.accept(message);if(NativePopupSmokeClient.enabled())NativePopupSmokeClient.accept(message);if(OpacityPersistenceSmokeClient.resuming())OpacityPersistenceSmokeClient.accept(message);}}
            case "conversationAgentProbe" -> {if(Boolean.getBoolean("mineagent.conversationAgentSmoke"))dev.mineagent.runtime.neoforge.client.chat.ConversationAgentSmokeClient.accept(message);}
            case "desktopWindowProbe" -> {if(Boolean.getBoolean("mineagent.desktopWindowsSmoke"))DesktopWindowsSmokeClient.accept(message);}
            case "workspaceSmokeProbe" -> {if(Boolean.getBoolean("mineagent.workspaceSmoke"))WorkspaceSmokeClient.accept(message);}
            case "livePlacementProbe" -> {if(!LivePlacementSmokeClient.mode().isEmpty())LivePlacementSmokeClient.accept(message);}
            case "viewLayout" -> {
                String view=text(message,"viewId",128);var descriptor=viewPackages.get(view);if(descriptor==null)throw new IllegalArgumentException("UI_LAYOUT_UNKNOWN_VIEW");
                if(!message.get("revision").isJsonPrimitive()||!message.get("revision").getAsJsonPrimitive().isNumber()||!message.get("revision").getAsString().matches("[0-9]{1,15}"))throw new IllegalArgumentException("UI_LAYOUT_REVISION");
                long revision=message.get("revision").getAsLong();var prior=viewLayouts.get(view);if(revision<1||prior!=null&&prior.get("revision").getAsLong()>=revision)return Map.of("status","STALE_LAYOUT");
                var vp=message.getAsJsonObject("viewport");double vw=vp.get("width").getAsDouble(),vh=vp.get("height").getAsDouble();if(!Double.isFinite(vw)||!Double.isFinite(vh)||vw<1||vh<1||vw>32768||vh>32768)throw new IllegalArgumentException("UI_LAYOUT_VIEWPORT");
                if(message.get("visible").getAsBoolean()){var b=message.getAsJsonObject("bounds");double x=b.get("x").getAsDouble(),y=b.get("y").getAsDouble(),w=b.get("width").getAsDouble(),h=b.get("height").getAsDouble();if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(w)||!Double.isFinite(h)||x< -1||y< -1||w<=0||h<=0||x+w>vw+1||y+h>vh+1)throw new IllegalArgumentException("UI_LAYOUT_BOUNDS");}
                if(message.has("opacity")){double alpha=message.get("opacity").getAsDouble();if(!Double.isFinite(alpha)||alpha<0||alpha>1)throw new IllegalArgumentException("UI_LAYOUT_OPACITY");}
                var value=message.deepCopy();value.addProperty("packageId",descriptor.packageId().toString());value.addProperty("packageRevision",descriptor.revision());value.addProperty("entry",descriptor.entry());viewLayouts.put(view,value);return Map.of("status","LAYOUT_OBSERVED","revision",revision);
            }
            case "presentationPaint" -> {return UiPresentationClient.paint(UUID.fromString(text(message,"requestId",64)));}
            case "restoreViewOpacity" -> {String view=text(message,"viewId",128);var layout=viewLayouts.get(view);if(!WebGuiAtlasCompositor.inputAvailable()||!viewPackages.containsKey(view)||UiClientSessions.current()==null||layout==null||layout.get("revision").getAsLong()!=message.get("expectedLayoutRevision").getAsLong())throw new IllegalStateException("STALE_LAYOUT");UiAgentClient.stop(view,true,"NATIVE_INPUT");PackagePageAgent.cancel(view);return Map.of("status","PLAYER_PRESENTATION_OVERRIDE_ALLOWED");}
            case "presentationAck" -> {UiPresentationClient.acknowledge(message);}
            case "worldMoveProbe" -> {if(Boolean.getBoolean("mineagent.worldMoveSmoke"))WorldInstanceMoveSmokeClient.accept(message);}
            case "worldUiModelProbe" -> {if(Boolean.getBoolean("mineagent.worldUiModelSmoke")||Boolean.getBoolean("mineagent.worldUiRepairSmoke"))WorldUiModelSmokeClient.accept(message);}
            case "worldUiRepairProbe" -> {if(Boolean.getBoolean("mineagent.worldUiRepairSmoke"))WorldUiRepairSmokeClient.accept(message);}
            case "worldUiAgentProbe" -> {if(Boolean.getBoolean("mineagent.worldUiAgentSmoke"))WorldUiAgentSmokeClient.accept(message);}
            case "personaProbe" -> {if(Boolean.getBoolean("mineagent.personaSmoke"))PersonaSmokeClient.accept(message);}
            case "conversationProbe" -> {if(Boolean.getBoolean("mineagent.conversationSmoke"))ConversationSmokeClient.accept(message);}
            case "worldPatchProbe" -> {if(Boolean.getBoolean("mineagent.worldPatchSmoke"))WorldPatchSmokeClient.accept(message);}
            case "settingsProbe" -> {if(Boolean.getBoolean("mineagent.settingsSmoke"))SettingsSmokeClient.accept(message);}
            case "agentManagementProbe" -> {if(Boolean.getBoolean("mineagent.agentManagementSmoke"))AgentManagementSmokeClient.accept(message);}
            case "packageAssetProbe" -> {if(Boolean.getBoolean("mineagent.packageAssetSmoke"))PackageAssetSmokeClient.accept(message);}
            case "craftingUseProbe" -> {if(Boolean.getBoolean("mineagent.craftingUseSmoke"))CraftingUseSmokeClient.accept(message);}
            case "decisionPage" -> UiClientSessions.decisionPage(message.get("page").getAsInt());
            case "hotSwapAck" -> ContentHotSwapClient.acknowledge(message);
            case "previewLoaded" -> {
                PackageUiStateClient.invalidate(text(message,"viewId",64));
                String view=text(message,"viewId",64); if(packageUrls.containsKey(view)) loadedPackages.add(view);
                String packageUrl=packageUrls.get(view);
                if(packageUrl!=null)for(long id:browser.getFrameIdentifiers()){
                    var frame=browser.getFrame(id);if(frame!=null&&!frame.isMain()&&packageUrl.equals(frame.getURL())){
                        if(viewPackages.get(view).passive())frame.executeJavaScript(dev.mineagent.runtime.client.webui.WebGuiTheme.passiveScript(),packageUrl,0);
                        if(viewSettings.get(view)==null||!viewSettings.get(view).appearance().equals("PACKAGE"))frame.executeJavaScript(dev.mineagent.runtime.client.webui.WebGuiTheme.packageScript()+"("+JSON.toJson(dev.mineagent.runtime.client.webui.WebGuiTheme.packageCss())+");",packageUrl,0);break;
                    }
                }
                PackageContentClient.loaded(view);
                PackageUiStateClient.install(view);
            }
            case "diagnostic" -> diagnostic = "PAGE_ERROR: " + text(message, "message", 500);
            case "sessionProbe" -> {
                if (!Boolean.getBoolean("mineagent.uiSessionSmokeTest")) throw new IllegalArgumentException("UNSUPPORTED");
                UiSessionSmokeClient.accept(message.deepCopy());
            }
            case "uiMultiProbe" -> {if(UiMultiplayerSmokeClient.enabled())UiMultiplayerSmokeClient.probe(message);}
            case "uiWorldProbe" -> {if(UiWorldSwitchSmokeClient.enabled())UiWorldSwitchSmokeClient.probe(message);}
            case "ysmJointProbe" -> {if(Boolean.getBoolean("mineagent.ysmJointSmoke"))YsmJointSmokeClient.probe(message);}
            case "appearanceRecoveryProbe" -> {if(dev.mineagent.runtime.neoforge.ui.YsmAppearanceRecoveryServer.enabled())YsmAppearanceRecoveryClient.probe(message);}
            case "appearanceAgentProbe" -> {if(dev.mineagent.runtime.neoforge.task.AppearanceAgentSmokeServer.enabled())AppearanceAgentSmokeClient.probe(message);}
            case "nativeApiProbe" -> {if(!Boolean.getBoolean("mineagent.nativeApiSmoke"))throw new IllegalArgumentException("UNSUPPORTED");NativeApiSmokeClient.accept(message.deepCopy());}
            case "resourcePackProbe" -> {if(!Boolean.getBoolean("mineagent.resourcePackSmoke"))throw new IllegalArgumentException("UNSUPPORTED");ResourcePackSmokeClient.accept(message.deepCopy());}
            case "clientScriptProbe" -> {if(ClientCodeDeliverySmokeClient.enabled())ClientCodeDeliverySmokeClient.acceptProbe(message.deepCopy());else if(Boolean.getBoolean("mineagent.clientStudioSmoke"))ClientStudioSmokeClient.accept(message.deepCopy());else if(Boolean.getBoolean("mineagent.clientScriptSmoke"))ClientScriptSmokeClient.accept(message.deepCopy());else if(Boolean.getBoolean("mineagent.clientJavaSmoke"))ClientJavaSmokeClient.accept(message.deepCopy());else if(Boolean.getBoolean("mineagent.clientDependencySmoke"))ClientDependencySmokeClient.accept(message.deepCopy());else throw new IllegalArgumentException("UNSUPPORTED");}
            case "bootUpgradeProbe" -> {if(Boolean.getBoolean("mineagent.bootUpgradeSmoke"))dev.mineagent.runtime.neoforge.boot.BootUpgradeSmokeClient.accept(message.deepCopy());else if(Boolean.getBoolean("mineagent.bootDependencySmoke"))dev.mineagent.runtime.neoforge.boot.BootDependencySmokeClient.accept(message.deepCopy());else throw new IllegalArgumentException("UNSUPPORTED");}
            case "taskAuthorityProbe" -> {if(TaskAuthoritySmokeClient.enabled())TaskAuthoritySmokeClient.probe(message);}
            case "decisionFlowProbe" -> {if(!Boolean.getBoolean("mineagent.decisionFlowSmoke"))throw new IllegalArgumentException("UNSUPPORTED");DecisionFlowSmokeClient.accept(message.deepCopy());}
            case "packageDeliveryProbe" -> {
                if (!Boolean.getBoolean("mineagent.packageDeliverySmoke")) throw new IllegalArgumentException("UNSUPPORTED");
                PackageDeliverySmokeClient.accept(message.deepCopy());
            }
            case "scoreUiProbe" -> {
                if(!Boolean.getBoolean("mineagent.scoreUiSmoke"))throw new IllegalArgumentException("UNSUPPORTED");
                ScoreUiSmokeClient.accept(message.deepCopy());
            }
            case "scoreHudProbe" -> {if(!Boolean.getBoolean("mineagent.scoreHudSmoke"))throw new IllegalArgumentException("UNSUPPORTED");ScoreHudSmokeClient.accept(message.deepCopy());}
            case "worldBoardProbe" -> {if(!Boolean.getBoolean("mineagent.worldBoardSmoke"))throw new IllegalArgumentException("UNSUPPORTED");WorldBoardSmokeClient.accept(message.deepCopy());}
            case "containerProbe" -> {if(!Boolean.getBoolean("mineagent.containerSmoke"))throw new IllegalArgumentException("UNSUPPORTED");ContainerSmokeClient.accept(message.deepCopy());}
            case "agentContainerProbe" -> {if(!Boolean.getBoolean("mineagent.agentContainerSmoke"))throw new IllegalArgumentException("UNSUPPORTED");AgentContainerSmokeClient.accept(message.deepCopy());}
            case "multiInputProbe" -> {if(!Boolean.getBoolean("mineagent.multiWindowInputSmoke"))throw new IllegalArgumentException("UNSUPPORTED");MultiWindowInputSmokeClient.accept(message.deepCopy());}
            case "takeoverProbe" -> {if(!Boolean.getBoolean("mineagent.scoreUiTakeover")&&!Boolean.getBoolean("mineagent.takeoverResumeSmoke"))throw new IllegalArgumentException("UNSUPPORTED");TakeoverSmokeClient.accept(message.deepCopy());}
            case "probeReply" -> {
                if (!Boolean.getBoolean("mineagent.webguiSmokeTest")) throw new IllegalArgumentException("UNSUPPORTED");
                WebGuiClientLifecycle.acceptProbe(message.deepCopy());
            }
            default -> throw new IllegalArgumentException("UNSUPPORTED_CHANNEL");
        }
        return Map.of("status", "RECEIVED");
    }

    private java.util.concurrent.CompletableFuture<?> handleRemote(JsonObject message) {
        if("desktopWindow".equals(text(message,"channel",64))){
            String kind=text(message,"kind",16);if(!Set.of("start","read","cancel").contains(kind))throw new IllegalArgumentException("WINDOW_PLAN_KIND");
            var data=new LinkedHashMap<String,String>();data.put("operationId",text(message,"operationId",36));
            if(kind.equals("start")){if(!(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("WINDOW_PLAN_NOT_INTERACTING");data.put("agentId",text(message,"agentId",36));data.put("title",text(message,"title",128));data.put("pinned",text(message,"pinned",5));data.put("prompt",text(message,"prompt",2048));}
            return UiClientSessions.command("desktop."+kind,data,kind.equals("start")?UUID.fromString(data.get("operationId")):UUID.randomUUID());
        }
        String channel = text(message, "channel", 64);
        if(channel.equals("deliveryOpen"))return ContentDeliveryClient.open(UUID.fromString(text(message,"deliveryId",36)),UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong());
        if(channel.equals("deliveryCodeDownload")){String mode=text(message,"mode",16);if(!Set.of("CLIENT_RHINO","CLIENT_JAVA").contains(mode))throw new IllegalArgumentException("DELIVERY_CODE_MODE");return java.util.concurrent.CompletableFuture.completedFuture(ClientScriptPackages.startDeliveryDownload(UUID.fromString(text(message,"deliveryId",36)),UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),text(message,"canonicalSha256",64),mode.equals("CLIENT_JAVA")));}
        if(channel.equals("feedbackHistory"))return UiClientSessions.command("feedback.history",Map.of("state",text(message,"state",24),"offset",text(message,"offset",8)),UUID.randomUUID());
        if(channel.equals("deliveryAction")){String action=text(message,"action",16);if(action.equals("feedback"))return UiClientSessions.command("feedback.list",Map.of("deliveryId",text(message,"deliveryId",36),"offset",Integer.toString(message.get("offset").getAsInt())),UUID.randomUUID());if(action.equals("feedback-read"))return UiClientSessions.command("feedback.read",Map.of("feedbackId",text(message,"feedbackId",36)),UUID.randomUUID());if(action.equals("list"))return UiClientSessions.command("delivery.list",Map.of("offset",Integer.toString(message.get("offset").getAsInt())),UUID.randomUUID());if(action.equals("reject")){String op=text(message,"operationId",36);return UiClientSessions.command("delivery.reject",Map.of("deliveryId",text(message,"deliveryId",36),"operationId",op),UUID.fromString(op));}throw new IllegalArgumentException("DELIVERY_SHELL_ACTION");}
        if(channel.equals("appearanceAction")){
            String action=text(message,"action",16),agent=text(message,"agentId",36);UUID.fromString(agent);
            if(action.equals("read"))return UiClientSessions.command("appearance.read",Map.of("agentId",agent),UUID.randomUUID());
            if(!Set.of("apply","decide").contains(action))throw new IllegalArgumentException("APPEARANCE_ACTION");
            return UiClientSessions.command(action.equals("decide")?"appearance.decide":"appearance.apply",Map.of("agentId",agent,"model",text(message,"model",128),"texture",text(message,"texture",128),"animation",text(message,"animation",128),"expectedRevision",Long.toString(message.get("expectedRevision").getAsLong())),UUID.fromString(text(message,"requestId",36)));
        }
        if(channel.equals("personaAction")){
            String action=text(message,"action",16),agent=text(message,"agentId",36);UUID.fromString(agent);
            if(action.equals("read"))return UiClientSessions.command("persona.read",Map.of("agentId",agent),UUID.randomUUID());
            if(!action.equals("save"))throw new IllegalArgumentException("PERSONA_ACTION");
            return UiClientSessions.command("persona.save",Map.of("agentId",agent,"text",text(message,"text",8192),"expectedRevision",message.get("expectedRevision").getAsString()),UUID.fromString(text(message,"requestId",36)));
        }
        if(channel.equals("conversationAction")){
            String kind=text(message,"kind",24);boolean read=Set.of("list","get","messages","message","summary","context","auditCandidates","auditPreview","auditJobs","auditJob","auditSource").contains(kind);
            if(!read&&!Set.of("create","rename","archive","delete","restore","send","cancel","focus","route","unfocus","voice","voiceCancel","speechDiscard","auditStart","auditStep").contains(kind))throw new IllegalArgumentException("CONVERSATION_ACTION");
            var data=new LinkedHashMap<String,String>();data.put("kind",kind);
            for(String key:java.util.List.of("agentId","conversationId","title","state","search","before","messageId","messageRevision","offset","expectedRevision","text","targetOperation","contextId","enabled","summaryId","sourceConversationId","upperSequence","availableRecords","identityHash","jobId","cursor","confirmed","speechOperation"))if(message.has(key)){
                var value=message.get(key);if(!value.isJsonPrimitive()||value.getAsString().length()>16384)throw new IllegalArgumentException("CONVERSATION_ARGUMENT");data.put(key,value.getAsString());
            }
            return UiClientSessions.command(read?"conversation.read":"conversation.write",data,read?UUID.randomUUID():UUID.fromString(text(message,"requestId",36)));
        }
        if(channel.equals("hudForget"))return HudPersistenceClient.forget(text(message,"key",512));
        if(channel.equals("hudResetPreferences"))return HudPersistenceClient.resetPreferences();
        if(channel.equals("hudRemember")){
            var b=message.getAsJsonObject("bounds");return HudPersistenceClient.remember(text(message,"viewId",64),message.get("enabled").getAsBoolean(),
                    new dev.mineagent.runtime.client.webui.HudRestoreEntry.Layout(b.get("x").getAsDouble(),b.get("y").getAsDouble(),b.get("width").getAsDouble(),b.get("height").getAsDouble(),message.get("minimized").getAsBoolean()));
        }
        var args = new LinkedHashMap<String, String>();
        if(channel.equals("deliveryDraftAction"))return DeliveryDraftClient.action(text(message,"viewId",128),text(message,"action",16));
        if(channel.equals("takeoverUi")){
            return switch(text(message,"action",16)){
                case "begin" -> ContentTakeoverClient.begin(text(message,"viewId",128));
                case "activate" -> ContentTakeoverClient.activate(text(message,"viewId",128),UUID.fromString(text(message,"operationId",36)));
                case "resume" -> ContentTakeoverClient.resume(UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),UUID.fromString(text(message,"targetViewId",36)));
                default -> throw new IllegalArgumentException("TAKEOVER_ACTION");
            };
        }
        if(channel.equals("delegateUi")){
            if(!message.has("confirmed")||!message.get("confirmed").getAsBoolean())throw new SecurityException("EXPLICIT_UI_DELEGATION_REQUIRED");
            return UiAgentClient.delegate(text(message,"viewId",128),UUID.fromString(text(message,"agentId",36)),text(message,"goal",8192),
                    text(message,"expectedTitle",256),UUID.fromString(text(message,"operationId",36)),message.has("presentationOnly")&&message.get("presentationOnly").getAsBoolean());
        }
        if(channel.equals("delegateWorldUi")){
            if(!message.has("confirmed")||!message.get("confirmed").getAsBoolean())throw new SecurityException("EXPLICIT_UI_DELEGATION_REQUIRED");
            return WorldUiAgentClient.start(text(message,"viewId",128),UUID.fromString(text(message,"agentId",36)),text(message,"goal",8192),text(message,"expected",8192),UUID.fromString(text(message,"operationId",36)));
        }
        if(channel.equals("settingsAction")){
            String kind=text(message,"kind",32);
            if(kind.equals("read"))return UiClientSessions.command("settings.read",Map.of(),UUID.randomUUID());
            if(kind.equals("secret")||kind.equals("asrSecret")||kind.equals("workflow")){
                var mc=Minecraft.getInstance();if(!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("NATIVE_SCREEN_BUSY");
                if(kind.equals("secret")||kind.equals("asrSecret"))mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.NativeSecretScreen(mc.screen,kind.equals("asrSecret")?"provider.asr.apiKey":"provider.openai.apiKey"));else mc.setScreen(dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen.workflowEditor(mc.screen));
                return java.util.concurrent.CompletableFuture.completedFuture(Map.of("code","NATIVE_SCREEN_OPEN"));
            }
            var values=new LinkedHashMap<String,String>();values.put("kind",kind);String revision=message.get("revision").getAsString();if(!revision.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("SETTINGS_REVISION_INVALID");values.put("revision",Long.toString(Long.parseLong(revision)));
            for(String key:java.util.List.of("confirmed","providerChangeConfirmed"))if(message.has(key)&&(!message.get(key).isJsonPrimitive()||!message.get(key).getAsJsonPrimitive().isBoolean()))throw new IllegalArgumentException("SETTINGS_BOOLEAN_INVALID");
            if(kind.equals("save")){values.put("values",message.getAsJsonObject("values").toString());values.put("providerChangeConfirmed",Boolean.toString(message.has("providerChangeConfirmed")&&message.get("providerChangeConfirmed").getAsBoolean()));}
            else if(kind.equals("permissions")){values.put("playerId",text(message,"playerId",36));values.put("actions",message.getAsJsonArray("actions").toString());values.put("confirmed",Boolean.toString(message.has("confirmed")&&message.get("confirmed").getAsBoolean()));}
            else throw new IllegalArgumentException("SETTINGS_ACTION_INVALID");
            return UiClientSessions.command("settings.write",values,UUID.fromString(text(message,"operationId",36)));
        }
        if(channel.equals("deliveryManagement")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            if(kind.equals("list")){args.put("status",text(message,"status",24));args.put("archive",text(message,"archive",16));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("detail"))args.put("deliveryId",text(message,"deliveryId",36));
            else if(kind.equals("write")){args.put("deliveryId",text(message,"deliveryId",36));args.put("expectedRevision",text(message,"expectedRevision",20));args.put("action",text(message,"action",16));if(!message.has("confirmed")||!message.get("confirmed").isJsonPrimitive()||!message.get("confirmed").getAsJsonPrimitive().isBoolean()||!message.get("confirmed").getAsBoolean())throw new IllegalArgumentException("DELIVERY_MANAGEMENT_CONFIRM");args.put("confirmed","true");}
            else throw new IllegalArgumentException("DELIVERY_MANAGEMENT_KIND");return UiClientSessions.command(kind.equals("write")?"delivery.manageWrite":"delivery.manageRead",args,kind.equals("write")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("bootExtension")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            switch(kind){case "list","diagnostics","plans","dependencies"->{args.put("offset",text(message,"offset",8));args.put("buildId",text(message,"buildId",36));}
                case "build"->{for(String key:Set.of("packageId","packageRevision","canonical","environment"))args.put(key,text(message,key,64));args.put("replacementBuildId",message.has("replacementBuildId")?text(message,"replacementBuildId",36):"");}
                case "change"->{for(String key:Set.of("buildId","expectedRevision","action","environment","confirmed","confirmModId"))args.put(key,text(message,key,128));}
                case "stageUpgrade"->{for(String key:Set.of("buildId","expectedRevision","environment","confirmed","confirmModId"))args.put(key,text(message,key,128));}
                case "cancelUpgrade"->{for(String key:Set.of("upgradeId","planHash","confirmed"))args.put(key,text(message,key,128));}
                default->throw new IllegalArgumentException("BOOT_ARGUMENTS");}
            boolean write=Set.of("build","change","stageUpgrade","cancelUpgrade").contains(kind);return UiClientSessions.command(write?"boot.change":"boot.read",args,write?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("nativeApi")){
            String kind=text(message,"kind",32);args.put("kind",kind);
            if(Set.of("modules","classes","members","method_body","source").contains(kind)){args.put("snapshot",text(message,"snapshot",64));args.put(kind.equals("source")?"lineOffset":"offset",text(message,"offset",8));}
            if(Set.of("classes","members").contains(kind)){args.put("module",text(message,"module",160));args.put("search",text(message,"search",128));}
            if(kind.equals("members")){args.put("class",text(message,"class",512));args.put("textOffset",text(message,"textOffset",8));}
            if(kind.equals("method_body")){args.put("module",text(message,"module",160));args.put("class",text(message,"class",512));args.put("method",text(message,"method",512));args.put("descriptor",text(message,"descriptor",2048));args.put("textOffset",text(message,"textOffset",8));}
            if(kind.equals("source")){args.put("module",text(message,"module",160));args.put("class",text(message,"class",512));if(message.has("method")||message.has("descriptor")){args.put("method",text(message,"method",512));args.put("descriptor",text(message,"descriptor",2048));}args.put("textOffset",text(message,"textOffset",8));}
            if(kind.equals("loaded")){args.put("search",text(message,"search",128));args.put("offset",text(message,"offset",8));}
            if(Set.of("live_members","live_method_body").contains(kind)){args.put("classToken",text(message,"classToken",36));args.put("offset",text(message,"offset",8));args.put("textOffset",text(message,"textOffset",8));}
            if(kind.equals("live_members"))args.put("search",text(message,"search",128));
            if(kind.equals("live_method_body")){args.put("method",text(message,"method",512));args.put("descriptor",text(message,"descriptor",2048));}
            if(Set.of("transformed_members","transformed_method_body").contains(kind)){args.put("snapshot",text(message,"snapshot",64));args.put("module",text(message,"module",160));args.put("class",text(message,"class",512));args.put("offset",text(message,"offset",8));args.put("textOffset",text(message,"textOffset",8));}
            if(kind.equals("transformed_members"))args.put("search",text(message,"search",128));
            if(kind.equals("transformed_method_body")){args.put("method",text(message,"method",512));args.put("descriptor",text(message,"descriptor",2048));}
            if(!Set.of("state","refresh","modules","classes","members","method_body","source","live_state","loaded","live_members","live_method_body","transformed_members","transformed_method_body").contains(kind))throw new IllegalArgumentException("NATIVE_API_QUERY");
            return UiClientSessions.command(kind.equals("refresh")?"nativeApi.refresh":"nativeApi.read",args,kind.equals("refresh")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("resourcePack")){
            var scope=ClientResourcePacks.webScope();String kind=text(message,"kind",24);
            try{
                if(kind.equals("download"))return java.util.concurrent.CompletableFuture.completedFuture(ClientResourcePacks.startDownload(UUID.fromString(text(message,"packageId",36)),Long.parseLong(text(message,"packageRevision",20)),text(message,"canonical",64)));
                if(kind.equals("read"))return java.util.concurrent.CompletableFuture.completedFuture(ClientResourcePacks.read(Integer.parseInt(text(message,"offset",8))));
                if(kind.equals("cancelDownload")){ClientResourcePacks.cancelDownload();return java.util.concurrent.CompletableFuture.completedFuture(Map.of("code","RESOURCE_PACK_DOWNLOAD_CANCELLED"));}
                if(kind.equals("change"))return java.util.concurrent.CompletableFuture.completedFuture(ClientResourcePacks.change(UUID.fromString(text(message,"operationId",36)),text(message,"filename",255),text(message,"action",16),Long.parseLong(text(message,"revision",20)),text(message,"selection",64),text(message,"environment",64),"true".equals(text(message,"confirmed",5)),"PLAYER:"+Minecraft.getInstance().player.getUUID(),scope));
                throw new IllegalArgumentException("RESOURCE_PACK_ARGUMENTS");
            }catch(Exception failure){return java.util.concurrent.CompletableFuture.completedFuture(Map.of("code",ClientResourcePacks.code(failure)));}
        }
        if(channel.equals("clientScript")){
            var scope=ClientScriptPackages.webScope();String kind=text(message,"kind",24);
            try{
                if(Set.of("download","downloadJava").contains(kind))return java.util.concurrent.CompletableFuture.completedFuture(ClientScriptPackages.startDownload(UUID.fromString(text(message,"packageId",36)),Long.parseLong(text(message,"packageRevision",20)),text(message,"canonical",64),kind.equals("downloadJava")));
                if(kind.equals("read"))return java.util.concurrent.CompletableFuture.completedFuture(ClientScriptPackages.read(Integer.parseInt(text(message,"offset",8))));
                if(kind.equals("cancelDownload")){ClientScriptPackages.cancelDownload();return java.util.concurrent.CompletableFuture.completedFuture(Map.of("code","CLIENT_SCRIPT_DOWNLOAD_CANCELLED"));}
                if(kind.equals("change"))return java.util.concurrent.CompletableFuture.completedFuture(ClientScriptPackages.change(UUID.fromString(text(message,"operationId",36)),text(message,"filename",255),text(message,"action",16),Long.parseLong(text(message,"revision",20)),text(message,"environment",64),"true".equals(text(message,"confirmed",5)),"PLAYER:"+Minecraft.getInstance().player.getUUID(),scope));
                throw new IllegalArgumentException("CLIENT_SCRIPT_ARGUMENTS");
            }catch(Exception failure){return java.util.concurrent.CompletableFuture.completedFuture(Map.of("code",ClientScriptPackages.code(failure)));}
        }
        if(channel.equals("dataPack")){
            String kind=text(message,"kind",16);for(String key:Set.of("packageId","packageRevision"))args.put(key,text(message,key,64));
            if(kind.equals("read")){args.put("offset",text(message,"offset",8));args.put("operationId",text(message,"operationId",36));}
            else if(kind.equals("change")){for(String key:Set.of("canonical","action","selection","environment","confirmed"))args.put(key,text(message,key,64));if(message.has("reopenOperation"))args.put("reopenOperation",text(message,"reopenOperation",36));}
            else throw new IllegalArgumentException("DATA_PACK_ARGUMENTS");
            return UiClientSessions.command(kind.equals("read")?"dataPack.read":"dataPack.change",args,kind.equals("read")?UUID.randomUUID():UUID.fromString(text(message,"operationId",36)));
        }
        if(channel.equals("worldRestore")){
            String kind=text(message,"kind",16);args.put("activationId",text(message,"activationId",36));
            if(kind.equals("resume")){for(String key:Set.of("instanceId","canonical","activationRevision","packageRevision","instanceRevision","environment","confirmed"))args.put(key,text(message,key,64));}
            else if(!kind.equals("read"))throw new IllegalArgumentException("RESTORE_REQUEST_INVALID");
            return UiClientSessions.command(kind.equals("read")?"world.restoreRead":"world.restoreResume",args,kind.equals("read")?UUID.randomUUID():UUID.fromString(text(message,"operationId",36)));
        }
        if(channel.equals("nativeCompatibility")){
            for(String key:Set.of("packageId","packageRevision","canonical","environment","pinRevision","approve","confirmed"))args.put(key,text(message,key,64));
            return UiClientSessions.command("world.compatibility",args,UUID.fromString(text(message,"operationId",36)));
        }
        if(channel.equals("javaStudio")){
            String kind=text(message,"kind",24);args.put("kind",kind);
            if(kind.equals("list"))args.put("offset",text(message,"offset",8));
            else if(kind.equals("coderList"))args.put("offset",text(message,"offset",8));
            else if(kind.equals("coderGet"))args.put("jobId",text(message,"jobId",36));
            else if(Set.of("coderFiles","coderFile").contains(kind)){for(String key:Set.of("jobId","revision","attempt","offset"))args.put(key,text(message,key,64));if(kind.equals("coderFile")){args.put("path",text(message,"path",128));args.put("hash",text(message,"hash",64));}}
            else if(Set.of("workspaceFiles","workspaceHistory","workspaceSource","dependencies").contains(kind)){for(String key:Set.of("draftId","revision","version","offset"))args.put(key,text(message,key,64));if(kind.equals("workspaceSource")){args.put("path",text(message,"path",128));args.put("hash",text(message,"hash",64));}}
            else if(kind.equals("dependencyCandidates")){for(String key:Set.of("draftId","revision","offset"))args.put(key,text(message,key,64));}
            else if(kind.equals("coderText")){for(String key:Set.of("jobId","revision","part","attempt","offset"))args.put(key,text(message,key,64));}
            else if(kind.equals("scriptCheck"))args.put("source",text(message,"source",16000));
            else if(Set.of("diagnostics","scriptDiag").contains(kind)){for(String key:Set.of("publicationId","publicationRevision","offset"))args.put(key,text(message,key,64));}
            else if(kind.equals("package")){for(String key:Set.of("packageId","packageRevision","targetSide","language","offset"))args.put(key,text(message,key,64));}
            else if(Set.of("get","source","draftHistory").contains(kind)){for(String key:Set.of("draftId","revision","offset"))args.put(key,text(message,key,64));}
            else if(kind.equals("historySource")){for(String key:Set.of("draftId","revision","offset","entryIndex","hash"))args.put(key,text(message,key,64));}
            else if(kind.equals("write")){
                args.remove("kind");String action=text(message,"action",24);args.put("action",action);args.put("confirmed",text(message,"confirmed",8));
                if(action.equals("workspaceFile")){for(String key:Set.of("draftId","revision","change","path","target"))args.put(key,text(message,key,128));args.put("source",text(message,"source",16000));}
                else if(action.equals("dependency")){for(String key:Set.of("draftId","revision","change","packageId","version"))args.put(key,text(message,key,64));if(message.has("packageRevision"))args.put("packageRevision",text(message,"packageRevision",24));}
                else if(action.equals("coderSubmit")){for(String key:Set.of("path","agentId","baseDraft","baseRevision","maxAttempts","diagnosticKind","publicationId"))args.put(key,text(message,key,128));args.put("prompt",text(message,"prompt",8192));if(message.has("workspace"))args.put("workspace",text(message,"workspace",8));if(message.has("shareDependencySources"))args.put("shareDependencySources",text(message,"shareDependencySources",8));for(String key:Set.of("nativeSnapshot","nativeClasses","nativeLiveSelections","shareNativeContext"))if(message.has(key))args.put(key,text(message,key,Set.of("nativeClasses","nativeLiveSelections").contains(key)?8192:64));}
                else if(action.equals("coderRepair")){for(String key:Set.of("jobId","revision","maxAttempts"))args.put(key,text(message,key,64));args.put("prompt",text(message,"prompt",8192));if(message.has("shareDependencySources"))args.put("shareDependencySources",text(message,"shareDependencySources",8));if(message.has("shareNativeContext"))args.put("shareNativeContext",text(message,"shareNativeContext",8));}
                else if(Set.of("coderCancel","coderAdopt").contains(action)){args.put("jobId",text(message,"jobId",36));args.put("revision",text(message,"revision",24));if(action.equals("coderAdopt"))args.put("sourceHash",text(message,"sourceHash",64));}
                else if(action.equals("create")){for(String key:Set.of("path","agentId","packageId","packageRevision","targetSide"))args.put(key,text(message,key,128));args.put("source",text(message,"source",16000));}
                else{args.put("draftId",text(message,"draftId",36));args.put("revision",text(message,"revision",24));switch(action){case "save"->args.put("source",text(message,"source",16000));case "publishSource"->{args.put("packageRevision",text(message,"packageRevision",24));args.put("name",text(message,"name",128));}case "stop"->{args.put("publicationId",text(message,"publicationId",36));args.put("publicationRevision",text(message,"publicationRevision",24));}case "run","stopLegacyScript"->{}default->throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");}}
            }else throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
            return UiClientSessions.command(kind.equals("write")?"studio.write":"studio.read",args,kind.equals("write")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("preferences")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            switch(kind){case "list","uses"->args.put("offset",text(message,"offset",8));case "receipt"->args.put("operationId",text(message,"operationId",36));case "preview"->{args.put("purpose",text(message,"purpose",24));args.put("agentId",text(message,"agentId",36));}
                case "change"->{for(String key:Set.of("action","id","expected","key","scope","agentId","purposes","enabled","confirmed"))args.put(key,text(message,key,128));args.put("value",text(message,"value",1024));}
                default->throw new IllegalArgumentException("PREFERENCE_ARGUMENTS");}
            return UiClientSessions.command(kind.equals("change")?"preferences.write":"preferences.read",args,kind.equals("change")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("packageAssets")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            switch(kind){case "inspect"->{for(String key:Set.of("packageId","packageRevision","canonical"))args.put(key,text(message,key,64));}
                case "shelf"->{args.put("active",text(message,"active",8));args.put("offset",text(message,"offset",8));}
                case "receipt"->args.put("operationId",text(message,"operationId",36));
                case "change"->{for(String key:Set.of("action","packageId","packageRevision","canonical","name","shelfId","expectedRevision","confirmed"))args.put(key,text(message,key,128));}
                default->throw new IllegalArgumentException("PACKAGE_ASSET_ARGUMENTS");}
            return UiClientSessions.command(kind.equals("change")?"package.assetsWrite":"package.assetsRead",args,kind.equals("change")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("packageCatalog")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            if(kind.equals("packages")){args.put("search",text(message,"search",128));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("operation")){args.put("category",text(message,"category",16));args.put("operation",text(message,"operation",36));}
            else if(Set.of("package","versions","version","versionText","versionFile","compatibility").contains(kind)){
                args.put("packageId",text(message,"packageId",36));args.put("headRevision",text(message,"headRevision",20));args.put("headHash",text(message,"headHash",64));
                if(!kind.equals("package"))args.put("offset",text(message,"offset",8));
                if(Set.of("version","versionText","versionFile").contains(kind)){args.put("versionRevision",text(message,"versionRevision",20));args.put("snapshotHash",text(message,"snapshotHash",64));}
                if(kind.equals("versionFile"))args.put("path",text(message,"path",256));
            }else throw new IllegalArgumentException("PACKAGE_CATALOG_KIND");return UiClientSessions.command("task.historyRead",args,UUID.randomUUID());
        }
        if(channel.equals("generationHistory")){
            String kind=text(message,"kind",16);args.put("kind",kind);args.put("category",text(message,"category",16));
            if(kind.equals("jobs")){args.put("state",text(message,"state",32));args.put("archive",text(message,"archive",16));args.put("offset",text(message,"offset",8));}
            else if(Set.of("job","jobPrompt","jobRaw").contains(kind)){args.put("operation",text(message,"operation",36));args.put("revision",text(message,"revision",20));if(!kind.equals("job"))args.put("offset",text(message,"offset",8));}
            else throw new IllegalArgumentException("PACKAGE_HISTORY_KIND");return UiClientSessions.command("task.historyRead",args,UUID.randomUUID());
        }
        if(channel.equals("taskHistory")){
            String kind=text(message,"kind",16);args.put("kind",kind);args.put("offset",text(message,"offset",8));
            if(kind.equals("list")){args.put("state",text(message,"state",24));args.put("archive",text(message,"archive",16));}
            else if(kind.equals("budget")){args.put("taskId",text(message,"taskId",36));}
            else if(Set.of("world","worldBatch","worldReceipt").contains(kind)){args.put("taskId",text(message,"taskId",36));if(!kind.equals("world"))args.put("batchId",text(message,"batchId",36));if(kind.equals("worldReceipt")){args.put("revision",text(message,"revision",20));args.put("receipt",text(message,"receipt",4));args.put("expectedHash",text(message,"expectedHash",64));}}
            else if(kind.equals("detail")||kind.equals("dependencies")||kind.equals("reason")){args.put("taskId",text(message,"taskId",36));if(!kind.equals("detail"))args.put("revision",text(message,"revision",20));if(kind.equals("dependencies"))args.put("stepId",text(message,"stepId",64));}
            else throw new IllegalArgumentException("TASK_HISTORY_KIND");return UiClientSessions.command("task.historyRead",args,UUID.randomUUID());
        }
        if(channel.equals("scheduleManagement")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            if(kind.equals("list")){args.put("state",text(message,"state",16));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("detail")){args.put("scheduleId",text(message,"scheduleId",36));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("deliveries")){args.put("scheduleId",text(message,"scheduleId",36));args.put("occurrenceId",text(message,"occurrenceId",36));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("write")){args.put("scheduleId",text(message,"scheduleId",36));args.put("action",text(message,"action",16));args.put("expectedRevision",text(message,"expectedRevision",20));if(!message.has("confirmed")||!message.get("confirmed").isJsonPrimitive()||!message.get("confirmed").getAsJsonPrimitive().isBoolean()||!message.get("confirmed").getAsBoolean())throw new IllegalArgumentException("SCHEDULE_MANAGEMENT_CONFIRM");args.put("confirmed","true");}
            else throw new IllegalArgumentException("SCHEDULE_MANAGEMENT_KIND");
            return UiClientSessions.command(kind.equals("write")?"task.schedulesWrite":"task.schedulesRead",args,kind.equals("write")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("eventManagement")){
            String kind=text(message,"kind",16);args.put("kind",kind);
            if(kind.equals("list")){args.put("mode",text(message,"mode",20));args.put("state",text(message,"state",20));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("detail")){args.put("subscriptionId",text(message,"subscriptionId",36));args.put("offset",text(message,"offset",8));}
            else if(kind.equals("state")||kind.equals("archive")){args.put("subscriptionId",text(message,"subscriptionId",36));if(kind.equals("state"))args.put("state",text(message,"state",16));args.put("expectedRevision",text(message,"expectedRevision",20));if(!message.has("confirmed")||!message.get("confirmed").isJsonPrimitive()||!message.get("confirmed").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("EVENT_MANAGEMENT_CONFIRM");args.put("confirmed",Boolean.toString(message.get("confirmed").getAsBoolean()));}
            else throw new IllegalArgumentException("EVENT_MANAGEMENT_KIND");
            boolean write=kind.equals("state")||kind.equals("archive");return UiClientSessions.command(write?"task.eventsWrite":"task.eventsRead",args,write?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
        }
        if(channel.equals("agentManagement")){
            if(message.has("offset")){int offset=message.get("offset").getAsInt();if(offset<0)throw new IllegalArgumentException("AGENT_PAGE");args.put("offset",Integer.toString(offset));}

            for(String key:java.util.List.of("kind","agentId","name","mode","playerId"))if(message.has(key))args.put(key,text(message,key,key.equals("name")?128:64));
            if(message.has("expectedRevision")){String revision=message.get("expectedRevision").getAsString();if(!revision.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("AGENT_REVISION_INVALID");args.put("expectedRevision",Long.toString(Long.parseLong(revision)));}
            for(String key:java.util.List.of("confirmed","enabled"))if(message.has(key)){if(!message.get(key).isJsonPrimitive()||!message.get(key).getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("AGENT_BOOLEAN_INVALID");args.put(key,Boolean.toString(message.get(key).getAsBoolean()));}
            return UiClientSessions.command("agent.manage",args,UUID.fromString(text(message,"operationId",36)));
        }
        if (channel.equals("packageAction")) {
            String action = text(message, "action", 32);
            if(action.equals("patchRebuild")){args.put("operationId",text(message,"operationId",36));args.put("rawHash",text(message,"rawHash",64));args.put("confirmed",Boolean.toString(message.has("confirmed")&&message.get("confirmed").getAsBoolean()));return UiClientSessions.command("package.patchRebuild",args,UUID.randomUUID());}
            if(Set.of("worldList","worldMoveInspect","worldMove").contains(action)){
                if(action.equals("worldList"))args.put("page",Integer.toString(message.get("page").getAsInt()));else args.put("activationId",text(message,"activationId",36));
                if(action.equals("worldMove")){args.put("instanceId",text(message,"instanceId",36));args.put("canonical",text(message,"canonical",64));args.put("activationRevision",Long.toString(message.get("activationRevision").getAsLong()));args.put("source",message.get("source").toString());for(String key:java.util.List.of("x","y","z"))args.put(key,Double.toString(message.get(key).getAsDouble()));args.put("confirmed",Boolean.toString(message.has("confirmed")&&message.get("confirmed").getAsBoolean()));}
                return UiClientSessions.command(switch(action){case "worldList"->"world.list";case "worldMoveInspect"->"world.moveInspect";default->"world.move";},args,message.has("operationId")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
            }
            if(Set.of("worldInspect","worldActivate","worldDisable","worldRestoreOff").contains(action)){
                if(action.equals("worldDisable")||action.equals("worldRestoreOff"))args.put("activationId",text(message,"activationId",36));
                else{args.put("packageId",text(message,"packageId",36));args.put("packageRevision",Long.toString(message.get("packageRevision").getAsLong()));}
                if(action.equals("worldRestoreOff"))args.put("activationRevision",Long.toString(message.get("activationRevision").getAsLong()));
                if(action.equals("worldActivate")){
                    args.put("definitionId",text(message,"definitionId",36));args.put("dimension",text(message,"dimension",128));
                    for(String key:java.util.List.of("x","y","z"))args.put(key,Double.toString(message.get(key).getAsDouble()));
                    args.put("confirmed",Boolean.toString(message.has("confirmed")&&message.get("confirmed").getAsBoolean()));
                    args.put("autoRestore",Boolean.toString(message.has("autoRestore")&&message.get("autoRestore").getAsBoolean()));
                }
                return UiClientSessions.command(switch(action){case "worldInspect"->"world.inspect";case "worldActivate"->"world.activate";case "worldRestoreOff"->"world.restoreOff";default->"world.disable";},args,message.has("operationId")?UUID.fromString(text(message,"operationId",36)):UUID.randomUUID());
            }
            if(action.equals("containerOpen")){
                if(!message.has("confirmed")||!message.get("confirmed").getAsBoolean())throw new SecurityException("CONTAINER_CONSENT_REQUIRED");
                return PackagePreviewClient.openContainer(UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong());
            }
            if(action.equals("containerAgent")){
                if(!message.has("confirmed")||!message.get("confirmed").getAsBoolean())throw new SecurityException("CONTAINER_CONSENT_REQUIRED");
                return PackagePreviewClient.openAgentContainer(UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),UUID.fromString(text(message,"agentId",36)),text(message,"goal",8192),text(message,"expected",4096));
            }
            if(Set.of("worldFront","worldMove","worldDetach","worldDelete").contains(action)){
                args.put("packageId",text(message,"packageId",36));args.put("packageRevision",Long.toString(message.get("packageRevision").getAsLong()));
                args.put("targetViewId",text(message,"targetViewId",36));args.put("expectedViewRevision",Long.toString(message.get("expectedViewRevision").getAsLong()));
                if(action.equals("worldMove"))for(String key:java.util.List.of("x","y","z","yaw","scale"))args.put(key,Double.toString(message.get(key).getAsDouble()));
                if(action.equals("worldMove")){args.put("dimension",text(message,"dimension",128));args.put("viewDistance",message.get("viewDistance").getAsString());}
                return UiClientSessions.command("scoreview."+action,args,UUID.fromString(text(message,"operationId",36)));
            }
            if(action.equals("preparePage"))return PageControlClient.prepare(text(message,"viewId",64));
            if(action.equals("taskStart"))return UiClientSessions.command("task.start",Map.of("agentId",text(message,"agentId",36),"prompt",text(message,"prompt",8192),"scope",message.has("scope")?text(message,"scope",32):"UI_PACKAGE"),UUID.fromString(text(message,"operationId",36)));
            if(action.equals("taskReplan")){
                if(!message.has("confirmed")||!message.get("confirmed").getAsBoolean())throw new SecurityException("TASK_REPLAN_CONFIRM_REQUIRED");
                return UiClientSessions.command("task.replan",Map.of("sourceTaskId",text(message,"sourceTaskId",36),"sourceRevision",Long.toString(message.get("sourceRevision").getAsLong()),"goal",text(message,"goal",8192),"note",text(message,"note",2048),"confirmed","true"),UUID.fromString(text(message,"operationId",36)));
            }
            if(action.equals("taskControl"))return UiClientSessions.command("task.control",Map.of("taskId",text(message,"taskId",36),"taskRevision",Long.toString(message.get("taskRevision").getAsLong()),"control",text(message,"control",16)),UUID.randomUUID());
            if(action.equals("patchSwapCancel")){UUID operation=UUID.fromString(text(message,"operationId",36));ContentHotSwapClient.cancel(operation);return java.util.concurrent.CompletableFuture.completedFuture(new dev.mineagent.runtime.api.ui.UiProtocol.Receipt(operation,dev.mineagent.runtime.api.ui.UiProtocol.Code.ACCEPTED,Map.of("state","STOP_REQUESTED")));}
            if(Set.of("patchApplySwap","patchUndoSwap").contains(action))return ContentHotSwapClient.apply(UUID.fromString(text(message,"operationId",36)),UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),action.equals("patchUndoSwap"));
            if(action.equals("patchPreview"))return PackagePreviewClient.openCandidate(UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),message.has("targetViewId")&&!message.get("targetViewId").getAsString().isBlank()?UUID.fromString(text(message,"targetViewId",36)):null,UUID.fromString(text(message,"operationId",36)));
            if(action.equals("patchSubmit")){
                args.put("packageId",text(message,"packageId",36));args.put("packageRevision",Long.toString(message.get("packageRevision").getAsLong()));args.put("agentId",text(message,"agentId",36));args.put("prompt",text(message,"prompt",8192));
                return UiClientSessions.command("package.patchSubmit",args,UUID.fromString(text(message,"operationId",36)));
            }
            if(action.equals("worldPatchSubmit"))return UiClientSessions.command("package.worldPatchSubmit",Map.of("packageId",text(message,"packageId",36),"packageRevision",Long.toString(message.get("packageRevision").getAsLong()),"agentId",text(message,"agentId",36),"prompt",text(message,"prompt",8192)),UUID.fromString(text(message,"operationId",36)));
            if(action.equals("worldPatchInspect"))return UiClientSessions.command("package.worldPatchInspect",Map.of("operationId",text(message,"operationId",36),"offset",message.has("offset")?message.get("offset").getAsString():"0","path",message.has("path")?text(message,"path",256):"","sourceOffset",message.has("sourceOffset")?message.get("sourceOffset").getAsString():"0","before",message.has("before")?Boolean.toString(message.get("before").getAsBoolean()):"false"),UUID.randomUUID());
            if(Set.of("worldPatchApply","worldPatchRollback","worldPatchCancel").contains(action))return UiClientSessions.command("package."+action,Map.of("operationId",text(message,"operationId",36),"confirmed",message.has("confirmed")?Boolean.toString(message.get("confirmed").getAsBoolean()):"false","canonicalSha256",message.has("canonicalSha256")?text(message,"canonicalSha256",64):""),UUID.randomUUID());
            if(Set.of("patchApply","patchRollback","patchCancel").contains(action)){args.put("operationId",text(message,"operationId",36));return UiClientSessions.command("package."+action,args,UUID.randomUUID());}
            if (action.equals("preview")) return PackagePreviewClient.open(UUID.fromString(text(message, "packageId", 36)), message.get("packageRevision").getAsLong());
            if (action.equals("open")) return PackagePreviewClient.open(UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),UUID.fromString(text(message,"targetViewId",36)));
            if (action.equals("hud")) return PackagePreviewClient.openHud(UUID.fromString(text(message,"packageId",36)),message.get("packageRevision").getAsLong(),UUID.fromString(text(message,"targetViewId",36)));
            if (action.equals("bind")) {
                args.put("packageId",text(message,"packageId",36));args.put("packageRevision",Long.toString(message.get("packageRevision").getAsLong()));
                args.put("sourceId",text(message,"sourceId",36));args.put("title",text(message,"title",2048));
                return UiClientSessions.command("scoreview.bind",args,UUID.fromString(text(message,"operationId",36)));
            }
            if (action.equals("generate")) {
                args.put("agentId", text(message, "agentId", 36)); args.put("prompt", text(message, "prompt", 8192));
                if(message.has("purpose"))args.put("purpose",text(message,"purpose",32));
                return UiClientSessions.command("package.generate", args, UUID.fromString(text(message, "operationId", 36)));
            }
            if(action.equals("repairGeneration")){
                if(!message.has("confirmed")||!message.get("confirmed").isJsonPrimitive()||!message.get("confirmed").getAsJsonPrimitive().isBoolean()||!message.get("confirmed").getAsBoolean())throw new IllegalArgumentException("GENERATION_REPAIR_CONSENT_REQUIRED");
                args.put("agentId",text(message,"agentId",36));args.put("prompt",text(message,"prompt",8192));args.put("repairSourceOperationId",text(message,"repairSourceOperationId",36));args.put("repairSourceRevision",message.get("repairSourceRevision").getAsString());args.put("repairSourceSha256",text(message,"repairSourceSha256",64));args.put("confirmed","true");
                dev.mineagent.runtime.core.packages.PackageGenerationRequest.parse(args);
                return UiClientSessions.command("package.generate",args,UUID.fromString(text(message,"operationId",36)));
            }
            if (action.equals("cancel")) {
                args.put("operationId", text(message, "operationId", 36));
                return UiClientSessions.command("package.cancel", args, UUID.randomUUID());
            }
            throw new IllegalArgumentException("PACKAGE_ACTION");
        }
        if (channel.equals("chatSend") || channel.equals("chatRefresh")) {
            args.put("agentId", text(message, "agentId", 36));
            if (channel.equals("chatSend")) args.put("text", text(message, "text", 8192));
            if(message.has("decisionId")){args.put("decisionId",text(message,"decisionId",36));args.put("decisionRevision",Long.toString(message.get("decisionRevision").getAsLong()));}
            return UiClientSessions.command(channel.equals("chatSend") ? "chat.send" : "chat.refresh", args, UUID.randomUUID());
        }
        String action = text(message, "action", 16);
        if (!java.util.Set.of("submit", "defer", "resume", "cancelTask").contains(action)) throw new IllegalArgumentException("DECISION_ACTION");
        args.put("decisionId", text(message, "decisionId", 36));
        args.put("expectedRevision", Long.toString(message.get("expectedRevision").getAsLong()));
        UUID operation = UUID.randomUUID();
        if (action.equals("submit")) {
            operation = UUID.fromString(text(message, "submissionId", 36));
            args.put("submissionId", operation.toString());
            var selected = message.getAsJsonArray("selectedOptionIds");
            if (selected.size() > 64) throw new IllegalArgumentException("SELECTION_COUNT");
            args.put("selectedCount", Integer.toString(selected.size()));
            for (int i = 0; i < selected.size(); i++) args.put("selected." + i, selected.get(i).getAsString());
            args.put("customText", textAllowEmpty(message, "customText", 8192));
        }
        return UiClientSessions.command("decision." + action, args, operation);
    }
    private static String textAllowEmpty(JsonObject message, String key, int limit) { return text(message, key, limit); }

    private static String text(JsonObject object, String key, int limit) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("UI_MESSAGE_TYPE");
        String result = value.getAsString();
        if (result.length() > limit) throw new IllegalArgumentException("UI_MESSAGE_LENGTH");
        return result;
    }

    /** Native revocation precedes the asynchronous shell visibility message. HUD sessions remain independent. */
    public void interactionMode(boolean active) {
        requireClientThread();
        if(!active)compositionActive=false;
        if(!active)for(var entry:viewPackages.entrySet())if(!entry.getValue().passive()){
            hiddenPackages.add(entry.getKey());PackageContentClient.visibility(entry.getKey(),false);PackagePageAgent.cancel(entry.getKey());
        }
        emit("workspaceMode",Map.of("visible",workspaceVisible));emit("interactionMode",Map.of("active",active));
    }
    public void emit(String channel, Object data) {
        requireClientThread();
        if (!ready() || !shell.entry("index.html").toString().equals(browser.getURL())) return;
        String detail = JSON.toJson(Map.of("channel", channel, "data", data));
        // Serialize as a string then JSON.parse: data is never inserted as executable script.
        browser.executeJavaScript("window.dispatchEvent(new CustomEvent('mineagent:host',{detail:JSON.parse("
                + JSON.toJson(detail) + ")}));", browser.getURL(), 0);
    }

    public void tick() {
        WebGuiAtlasCompositor.tick();WebGuiPopupCompositor.tick();
        UiClientSessions.tick();
        if (browser == null) return;
        String paintFailure=McefPaintBoundary.takeFailure(browser);if(paintFailure!=null){boolean show=Minecraft.getInstance().screen instanceof WebGuiInteractionScreen;close();diagnostic=paintFailure;if(show)showDiagnostic();return;}
        if (connection != Minecraft.getInstance().getConnection() || WebSession.hudBrowser() != browser) { close(); return; }
        if (!gate.documentAllowed(browser, browser.getURL(), ready)) {
            close(); diagnostic = "STALE_VIEW: 宿主页已导航"; return;
        }
        if (!ready && System.nanoTime() - openNanos > 15_000_000_000L) {
            boolean show=!backgroundOpen&&Minecraft.getInstance().screen instanceof WebGuiInteractionScreen;close(); diagnostic = "PAGE_READY_TIMEOUT"; if(show)showDiagnostic(); return;
        }
        if (ready && System.nanoTime() - lastSnapshotNanos > 500_000_000L) snapshot();
    }

    private String previousSnapshot = "";
    private void snapshot() {
        lastSnapshotNanos = System.nanoTime();
        var values = connection == null ? Map.<String, String>of() : PanelSnapshotInbox.conversationState().values();
        StringBuilder history = new StringBuilder();
        int count;
        try { count = Math.max(0, Math.min(40, Integer.parseInt(values.getOrDefault("messageCount", "0")))); }
        catch (NumberFormatException malformed) { count = 0; }
        for (int i = Math.max(0, count - 8); i < count; i++) history.append(values.getOrDefault("message." + i + ".role", ""))
                .append(": ").append(values.getOrDefault("message." + i + ".text", "")).append('\n');
        String value = history.substring(0, Math.min(24_000, history.length()));
        String signature = (connection != null) + "\n" + value;
        if (!signature.equals(previousSnapshot)) {
            previousSnapshot = signature;
            emit("snapshot", Map.of("connected", connection != null, "conversationText", value));
        }
    }

    private void showDiagnostic() { Minecraft.getInstance().setScreen(new WebGuiDiagnosticScreen(diagnostic)); }
    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("CLIENT_THREAD_REQUIRED");
    }

    @Override public void close() {
        requireClientThread();
        WebGuiPopupCompositor.clear();McefPaintBoundary.discard(browser);
        WebGuiPaintComposition.clear();
        HudPersistenceClient.hostClosed();
        UiClientSessions.reset(true);
        PageControlClient.clear();
        UiPresentationClient.clear();RendererSettingsClient.clear();
        WebGuiNativeInput.clear();
        PackageViewCapture.clear();
        PackagePageAgent.clear();
        dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.clearWebNotice();
        gate.clear(); ready = false;
        if (browser != null && WebSession.hudBrowser() == browser) {
            WebSession.closeHudOnly(); WebHudOverlay.reset();
        } else if (browser != null) browser.close();
        browser = null; connection = null; packageMounts.clear(); packageUrls.clear(); loadedPackages.clear();viewPackages.clear();hiddenPackages.clear();viewSettings.clear();viewLayouts.clear();
        if (resources != null) resources.close();
        resources = null; shell = null; previousSnapshot = "";
        standaloneOpen=false;workspaceVisible=false;compositionActive=false;
        diagnostic = "VIEW_NOT_RENDERED";
        if (Minecraft.getInstance().screen instanceof WebGuiInteractionScreen) Minecraft.getInstance().setScreen(null);
        // One router per CEF client lifetime, not one router per window/reopen.
    }
}
