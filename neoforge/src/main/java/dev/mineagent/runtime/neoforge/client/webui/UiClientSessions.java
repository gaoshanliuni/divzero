package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class UiClientSessions {
    private static final Gson JSON = new Gson();
    private static final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private static Session session;
    private static UUID opening;
    private static long openingDeadline;
    private static boolean rendered, refreshing;
    private static long nextRefresh;
    private static int viewPage;
    private static int decisionPage,decisionWatchCursor;
    private static final Set<UUID> decisionWatches=new LinkedHashSet<>();
    public static void decisionPage(int page){if(page<0||page>100000)throw new IllegalArgumentException("DECISION_PAGE");decisionPage=page;nextRefresh=0;}
    public static void decisionWatch(String view,boolean closed){
        if(!view.startsWith("decision-"))return;
        try{var id=UUID.fromString(view.substring(9));if(closed)decisionWatches.remove(id);else if(decisionWatches.size()<64)decisionWatches.add(id);}
        catch(IllegalArgumentException ignored){/* A local window ID is not decision authority. */}
    }
    public static void viewPage(int page){if(page<0||page>100000)throw new IllegalArgumentException("VIEW_PAGE");viewPage=page;nextRefresh=0;}
    private static Object connection;
    private static String lastState = "";
    private static String lastPollError = "";
    private static String stateScope;
    private static final java.util.concurrent.Executor STATE_IO = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), r -> {
                Thread thread = new Thread(r, "mineagent-ui-state"); thread.setDaemon(true); return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private record Pending(Request request, String channel, CompletableFuture<Receipt> future, long deadline) {}
    private UiClientSessions() {}
    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(UiPayloads.Event.TYPE, (p, ctx) -> {var source=ctx.connection();ctx.enqueueWork(() -> {
            var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=source)return;
            if(!StatePushClient.accept(p)&&!ClientCodeDeliverySmokeClient.accept(p)&&!DeliverySmokeClient.accept(p)&&!ContentDeliveryClient.accept(p)&&!SharedMultiplayerSmokeClient.accept(p)&&!WorldUiClient.accept(p,source)&&!TaskAuthoritySmokeClient.accept(p)&&!UiWorldSwitchSmokeClient.accept(p)&&!UiMultiplayerSmokeClient.accept(p)&&!HudPersistenceClient.accept(p,source))accept(p);
        });});
    }
    public static Session current() { return rendered ? session : null; }
    public static void open() {
        if (Minecraft.getInstance().getConnection() == null || opening != null || rendered) return;
        connection = Minecraft.getInstance().getConnection(); opening = UUID.randomUUID(); openingDeadline = System.currentTimeMillis() + 10_000;
        ClientPacketDistributor.sendToServer(new UiPayloads.Command(opening, "openShell", "{}"));
    }
    private static void accept(UiPayloads.Event packet) {
        if(packet.channel().equals("entityModelInspect")){dev.mineagent.runtime.neoforge.client.objects.EntityPartModels.inspect(packet);return;}
        if(Set.of("entityVisualReset","entityVisualRule","entityAnimationInspect").contains(packet.channel())){dev.mineagent.runtime.neoforge.client.objects.EntityVisualClient.accept(packet);return;}
        if(packet.channel().equals("nativeChatStream")){dev.mineagent.runtime.neoforge.client.chat.NativeStreamingChat.accept(packet);return;}
        if(packet.channel().equals("chatMessageDisplay")){dev.mineagent.runtime.neoforge.client.chat.ChatMessageDisplayClient.accept(packet);return;}
        if(packet.channel().equals("nativeThinkingSetting")){var mode=JsonParser.parseString(packet.json()).getAsJsonObject().get("mode").getAsString();var old=dev.mineagent.runtime.neoforge.client.chat.NativeChatPreferencesClient.view();boolean value=mode.equals("toggle")?!((Boolean)old.get("showThinking")):mode.equals("on");dev.mineagent.runtime.neoforge.client.chat.NativeChatPreferencesClient.save(value,((Number)old.get("revision")).longValue()).whenComplete((v,e)->Minecraft.getInstance().execute(()->{if(Minecraft.getInstance().player!=null)Minecraft.getInstance().gui.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(e==null?dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("原生聊天思考：")+(value?dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("显示"):dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("隐藏")):dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("思考显示设置失败")));}));return;}
        if(packet.channel().equals("skinUiOpen")){SkinUiClient.open(JsonParser.parseString(packet.json()).getAsJsonObject().get("agentId").getAsString());return;}
        if(packet.channel().equals("previewOpen")){PreviewClient.open(JsonParser.parseString(packet.json()).getAsJsonObject().get("previewId").getAsString());return;}
        if(packet.channel().equals("buildingFilesOpen")){var fileEvent=JsonParser.parseString(packet.json()).getAsJsonObject();BuildingFilesClient.requestOpen(fileEvent.get("agentId").getAsString(),fileEvent.has("fileId")?fileEvent.get("fileId").getAsString():"");return;}
        if (connection != Minecraft.getInstance().getConnection() || !WebGuiHostAdapter.INSTANCE.ready()) return;
        try {
            if(packet.channel().equals("conversationChanged")){WebGuiHostAdapter.INSTANCE.emit("conversationChanged",JsonParser.parseString(packet.json()));return;}
            if(packet.channel().equals("conversationVoiceStatus")){WebGuiHostAdapter.INSTANCE.emit("conversationVoiceStatus",JsonParser.parseString(packet.json()));return;}
            if(packet.channel().equals("packageViewOutdated")){var data=JsonParser.parseString(packet.json()).getAsJsonObject();PackageContentClient.outdated(data.get("viewId").getAsString(),UUID.fromString(data.get("sessionId").getAsString()));return;}
            if(UiAgentClient.accept(packet))return;
            if (packet.channel().equals("error") && packet.requestId().equals(opening)) {
                opening = null;
                String code="SESSION_ADMISSION_REJECTED";
                try{var detail=JsonParser.parseString(packet.json()).getAsJsonObject();if(detail.has("code")&&detail.get("code").getAsString().equals("WORLD_IDENTITY_NOT_READY"))code=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("存档身份尚未接入：请所有者/管理员执行 /ai identity，确认后保存并重新打开世界。");}catch(RuntimeException ignored){}
                WebGuiHostAdapter.INSTANCE.emit("sessionError", Map.of("code", code));
                return;
            }
            if (packet.channel().equals("session") && packet.requestId().equals(opening)) {
                Session candidate = JSON.fromJson(packet.json(), Session.class);
                var player = Minecraft.getInstance().player;
                if (player == null || !candidate.binding().viewerPlayerId().equals(player.getUUID())
                        || !candidate.binding().actorId().equals(player.getUUID()) || candidate.binding().actorKind() != ActorKind.PLAYER)
                    throw new IllegalArgumentException("SESSION_BINDING_MISMATCH");
                session = candidate; opening = null;
                var server = Minecraft.getInstance().getCurrentServer();
                stateScope = (server == null ? "integrated" : server.ip) + "|" + candidate.binding().worldId() + "|" + player.getUUID();
                String loadingScope = stateScope;
                var store = stateStore();
                CompletableFuture.supplyAsync(() -> {
                    try { return store.load(loadingScope); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
                }, STATE_IO).whenComplete((value, failure) -> Minecraft.getInstance().execute(() -> {
                    if (session != candidate || !Objects.equals(stateScope, loadingScope)) return;
                    if (failure == null) {
                        try { WebGuiHostAdapter.INSTANCE.emit("uiStateRestore", JsonParser.parseString(value)); }
                        catch (RuntimeException corrupt) { WebGuiHostAdapter.INSTANCE.emit("sessionError", Map.of("code", "UI_DRAFT_INVALID")); }
                    } else WebGuiHostAdapter.INSTANCE.emit("sessionError", Map.of("code", "UI_DRAFT_LOAD_FAILED"));
                }));
                request("rendered", new Request(UUID.randomUUID(), candidate.sessionId(), candidate.pageGeneration(), candidate.controlEpoch(),
                        candidate.binding().taskRevision(), "shell.read", Map.of())).whenComplete((r, error) -> {
                    if (error == null && r.code() == Code.OK && session == candidate) {
                        rendered = true;
                        WebGuiHostAdapter.INSTANCE.emit("session", candidate);
                        HudPersistenceClient.sessionReady(candidate);
                        nextRefresh = 0;
                    }
                });
                return;
            }
            Pending p = pending.remove(packet.requestId());
            if (p == null) return;
            if (packet.channel().equals("receipt")){var receipt=JSON.fromJson(packet.json(), Receipt.class);DecisionFlowSmokeClient.receipt(p.request,receipt);p.future.complete(receipt);}
            else p.future.completeExceptionally(new IllegalStateException("UI_SERVER_REJECTED"));
        } catch (RuntimeException failure) {
            Pending p = pending.remove(packet.requestId());
            if (p != null) p.future.completeExceptionally(failure);
        }
    }
    public static CompletableFuture<Receipt> command(String action, Map<String, String> values, UUID operationId) {
        if (!rendered || session == null || connection != Minecraft.getInstance().getConnection())
            return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        return request("command", new Request(operationId, session.sessionId(), session.pageGeneration(), session.controlEpoch(),
                session.binding().taskRevision(), action, values));
    }
    public static CompletableFuture<Receipt> contentRequest(String channel, Session content, String action, Map<String,String> values, UUID operationId) {
        if (connection == null || connection != Minecraft.getInstance().getConnection() || !WebGuiHostAdapter.INSTANCE.ready())
            return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        return request(channel,new Request(operationId,content.sessionId(),content.pageGeneration(),content.controlEpoch(),content.binding().taskRevision(),action,values));
    }
    private static CompletableFuture<Receipt> request(String channel, Request request) {
        Pending old = pending.get(request.operationId());
        if (old != null) return old.request.equals(request) && old.channel.equals(channel) ? old.future
                : CompletableFuture.failedFuture(new IllegalStateException("OPERATION_ID_REUSED"));
        if (pending.size() >= 64) return CompletableFuture.failedFuture(new IllegalStateException("UI_PENDING_BUDGET"));
        var future = new CompletableFuture<Receipt>();
        pending.put(request.operationId(), new Pending(request, channel, future, System.currentTimeMillis() + 10_000));
        var packet = new UiPayloads.Command(request.operationId(), channel, JSON.toJson(request));
        UiMultiplayerSmokeClient.sent(channel,request);
        SharedMultiplayerSmokeClient.sent(channel,request);
        TaskAuthoritySmokeClient.sent(channel,request);
        AgentManagementSmokeClient.sent(channel,request);
        ClientPacketDistributor.sendToServer(packet);
        if(UiMultiplayerSmokeClient.enabled()&&request.action().equals("decision.submit"))ClientPacketDistributor.sendToServer(packet);
        if(Boolean.getBoolean("mineagent.decisionFlowSmoke")&&request.action().equals("decision.submit"))ClientPacketDistributor.sendToServer(packet);
        if(Boolean.getBoolean("mineagent.worldUiSmoke")&&request.action().equals("worldui.action"))ClientPacketDistributor.sendToServer(packet);
        if(Boolean.getBoolean("mineagent.containerSmoke")&&request.action().equals("container.act"))ClientPacketDistributor.sendToServer(packet);
        if (Boolean.getBoolean("mineagent.uiSessionSmokeTest") && request.action().equals("decision.submit"))
            ClientPacketDistributor.sendToServer(packet); // explicit wire-duplication fixture, not a second UI decision
        if (Boolean.getBoolean("mineagent.scoreUiSmoke") && request.action().equals("scoreview.patch"))
            ClientPacketDistributor.sendToServer(packet); // exact replay fixture; must not advance view revision twice
        return future;
    }
    public static void tick() {
        PackagePreviewClient.tick();ClientResourcePacks.tick();ClientScriptPackages.tick();
        long now = System.currentTimeMillis();
        if (connection != null && connection != Minecraft.getInstance().getConnection()) { reset(false); return; }
        if (opening != null && now >= openingDeadline) { opening = null; WebGuiHostAdapter.INSTANCE.emit("sessionError", Map.of("code", "SESSION_TIMEOUT")); }
        dev.mineagent.runtime.client.webui.UiPendingCallbacks.expire(pending,p->p.deadline<=now,p->p.future.completeExceptionally(new IllegalStateException("UI_OPERATION_TIMEOUT")));
        if (!rendered || refreshing || now < nextRefresh) return;
        nextRefresh = now + 1000; refreshing = true;
        var args=new LinkedHashMap<String,String>();args.put("viewPage",Integer.toString(viewPage));args.put("decisionPage",Integer.toString(decisionPage));
        if(!decisionWatches.isEmpty()){var watched=new ArrayList<>(decisionWatches);args.put("watchDecisionId",watched.get(Math.floorMod(decisionWatchCursor++,watched.size())).toString());}
        var polling=session;var pollingConnection=connection;
        command("shell.read", args, UUID.randomUUID()).whenComplete((r, error) -> {
            if(!dev.mineagent.runtime.client.webui.UiPendingCallbacks.current(polling,session,pollingConnection,connection,rendered)||connection!=Minecraft.getInstance().getConnection())return;
            refreshing = false;
            if (error != null || r.code() != Code.OBSERVED) {
                String code = error != null ? "UI_SNAPSHOT_TIMEOUT" : r.code().name();
                if(error==null&&"DECISION_CARD_BUDGET".equals(r.values().get("errorCode")))code="DECISION_CARD_BUDGET";
                if (!code.equals(lastPollError)) { lastPollError = code; WebGuiHostAdapter.INSTANCE.emit("sessionError", Map.of("code", code)); }
                if (error == null && Set.of(Code.EXPIRED, Code.VIEW_NOT_RENDERED, Code.STALE_VIEW).contains(r.code())) {
                    reset(false); open(); // Renew subscriptions only. Never re-send a mutation.
                } else if (error == null && r.code() == Code.PERMISSION_DENIED) rendered = false;
                return;
            }
            lastPollError = "";
            String state = JSON.toJson(r.values());
            if (state.equals(lastState)) return;
            lastState = state;
            WebGuiHostAdapter.INSTANCE.emit("decisions", JsonParser.parseString(r.values().getOrDefault("decisions", "[]")));
            if(r.values().containsKey("decisionUpdate")){
                var update=new com.google.gson.JsonObject();update.add("request",JsonParser.parseString(r.values().get("decisionUpdate")));
                if(r.values().containsKey("decisionAnswer"))update.add("answer",JsonParser.parseString(r.values().get("decisionAnswer")));
                if(r.values().containsKey("decisionEffect"))update.add("effect",JsonParser.parseString(r.values().get("decisionEffect")));
                WebGuiHostAdapter.INSTANCE.emit("decisionUpdate",update);
            }
            WebGuiHostAdapter.INSTANCE.emit("decisionPaging",JsonParser.parseString(r.values().getOrDefault("decisionPaging","{\"page\":0,\"pages\":1,\"count\":0,\"pendingCount\":0}")));
            WebGuiHostAdapter.INSTANCE.emit("decisionContexts",JsonParser.parseString(r.values().getOrDefault("decisionContexts","[]")));
            WebGuiHostAdapter.INSTANCE.emit("agents", JsonParser.parseString(r.values().getOrDefault("agents", "[]")));
            WebGuiHostAdapter.INSTANCE.emit("worldTasks",JsonParser.parseString(r.values().getOrDefault("worldTasks","[]")));
            WebGuiHostAdapter.INSTANCE.emit("settingsVersion",JsonParser.parseString(r.values().getOrDefault("settingsVersion","{}")));
            WebGuiHostAdapter.INSTANCE.emit("agentManagement",JsonParser.parseString(r.values().getOrDefault("agentManagement","{}")));
            WebGuiHostAdapter.INSTANCE.emit("generationJobs", JsonParser.parseString(r.values().getOrDefault("generationJobs", "[]")));
            WebGuiHostAdapter.INSTANCE.emit("scoreSources", JsonParser.parseString(r.values().getOrDefault("scoreSources", "[]")));
            WebGuiHostAdapter.INSTANCE.emit("packageHeads",JsonParser.parseString(r.values().getOrDefault("packageHeads","[]")));
            WebGuiHostAdapter.INSTANCE.emit("worldPatchJobs",JsonParser.parseString(r.values().getOrDefault("worldPatchJobs","[]")));
            WebGuiHostAdapter.INSTANCE.emit("patchJobs",JsonParser.parseString(r.values().getOrDefault("patchJobs","[]")));
            WebGuiHostAdapter.INSTANCE.emit("scoreViews", JsonParser.parseString(r.values().getOrDefault("scoreViews", "[]")));
            WebGuiHostAdapter.INSTANCE.emit("scoreViewPaging",JsonParser.parseString(r.values().getOrDefault("scoreViewPaging","{\"page\":0,\"pages\":1,\"count\":0}")));
            WebGuiHostAdapter.INSTANCE.emit("uiAgentTasks",JsonParser.parseString(r.values().getOrDefault("uiAgentTasks","[]")));
        });
    }
    public static void reset(boolean notifyServer) {
        dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.clear(null);
        PackageUiStateClient.clear();
        if (notifyServer || connection != Minecraft.getInstance().getConnection()) PackageContentClient.clear();
        PackagePreviewClient.cancel();ClientResourcePacks.cancelDownload();ClientScriptPackages.cancelDownload();
        if (notifyServer && session != null && Minecraft.getInstance().getConnection() == connection) {
            var r = new Request(UUID.randomUUID(), session.sessionId(), session.pageGeneration(), session.controlEpoch(),
                    session.binding().taskRevision(), "close", Map.of());
            ClientPacketDistributor.sendToServer(new UiPayloads.Command(r.operationId(), "close", JSON.toJson(r)));
        }
        var copy = new ArrayList<>(pending.values()); pending.clear();
        session = null; rendered = false; refreshing = false; opening = null; connection = null; lastState = ""; stateScope = null; lastPollError = "";
        viewPage=0;
        decisionPage=0;decisionWatchCursor=0;decisionWatches.clear();
        copy.forEach(p -> p.future.completeExceptionally(new IllegalStateException("USER_INTERRUPTED")));
    }
    private static dev.mineagent.runtime.client.webui.UiStateStore stateStore() {
        return new dev.mineagent.runtime.client.webui.UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/ui-state"));
    }
    public static CompletableFuture<Map<String, String>> persist(String value) {
        String scope = stateScope;
        if (scope == null || !rendered) return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        HudPersistenceClient.updateLayouts(JsonParser.parseString(value).getAsJsonObject().getAsJsonObject("layouts"));
        var store = stateStore();
        return CompletableFuture.supplyAsync(() -> {
            try { store.save(scope, value); return Map.of("status", "DRAFT_SAVED"); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, STATE_IO);
    }
}
