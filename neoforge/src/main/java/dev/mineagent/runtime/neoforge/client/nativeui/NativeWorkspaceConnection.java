package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Trusted built-in workspace protocol. Independent of browser and KubeJS initialization. */
public final class NativeWorkspaceConnection {
    private static final Gson JSON=new Gson();
    private record Awaiting(String action,Map<String,String> values,UUID operation,Object connection,Object level,Object player,long deadline,CompletableFuture<Receipt> future){}
    private static final Map<UUID,Awaiting> AWAITING=new LinkedHashMap<>();
    private static String text(String value){return dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(value);}
    private static void failAwaiting(String reason){var waiting=List.copyOf(AWAITING.values());AWAITING.clear();for(var pending:waiting)pending.future.completeExceptionally(new IllegalStateException(reason));}
    private static void flushAwaiting(){
        var mc=Minecraft.getInstance();for(var pending:List.copyOf(AWAITING.values())){
            if(pending.connection!=mc.getConnection()||pending.level!=mc.level||pending.player!=mc.player){AWAITING.remove(pending.operation);pending.future.completeExceptionally(new IllegalStateException(text("世界或玩家已变化，操作尚未发送。")));}
            else if(System.currentTimeMillis()>=pending.deadline){AWAITING.remove(pending.operation);pending.future.completeExceptionally(new IllegalStateException(text("工作区连接超时，操作尚未发送。")));}
            else if(ready()){AWAITING.remove(pending.operation);request("command",session,pending.action,pending.values,pending.operation).whenComplete((receipt,error)->{if(error==null)pending.future.complete(receipt);else pending.future.completeExceptionally(error);});}
        }
    }
    private record Pending(Request request,String channel,CompletableFuture<Receipt> future,long deadline){}
    private static final Map<UUID,Pending> PENDING=new HashMap<>();
    private static Session session;private static UUID opening;private static Object connection,level;private static long deadline,nextPoll;private static boolean rendered,polling;
    static Map<String,Object> diagnostic(){return Map.of("enabled",dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled(),"ready",ready(),"rendered",rendered,"opening",opening!=null,"awaiting",AWAITING.size(),"pending",PENDING.size());}
    public static Session current(){return ready()?session:null;}
    public static boolean ready(){return rendered&&session!=null&&connection==Minecraft.getInstance().getConnection()&&level==Minecraft.getInstance().level;}
    public static void open(){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.player==null||ready()||opening!=null||session!=null&&connection==mc.getConnection()&&level==mc.level)return;
        if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled()){dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.showChoice(true);return;}
        connection=mc.getConnection();level=mc.level;opening=UUID.randomUUID();deadline=System.currentTimeMillis()+10000;
        ClientPacketDistributor.sendToServer(new UiPayloads.Command(opening,"openShell","{}"));
    }
    public static boolean accept(UiPayloads.Event packet){
        if(connection!=Minecraft.getInstance().getConnection()||level!=Minecraft.getInstance().level)return false;
        if(opening!=null&&opening.equals(packet.requestId())){
            opening=null;
            if(!packet.channel().equals("session")){failAwaiting(text("工作区连接失败，操作尚未发送。"));NativeWorkspaceScreen.notice(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("连接服务端失败：")+packet.json());return true;}
            var candidate=JSON.fromJson(packet.json(),Session.class);var mc=Minecraft.getInstance();
            if(mc.player==null||!candidate.binding().viewerPlayerId().equals(mc.player.getUUID())||!candidate.binding().actorId().equals(mc.player.getUUID())||candidate.binding().actorKind()!=ActorKind.PLAYER)throw new IllegalArgumentException("NATIVE_WORKSPACE_BINDING");
            session=candidate;request("rendered",candidate,"shell.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->{
                if(session!=candidate)return;if(error!=null||receipt.code()!=Code.OK){session=null;failAwaiting(text("工作区连接失败，操作尚未发送。"));NativeWorkspaceScreen.notice(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("工作区会话未就绪"));return;}
                rendered=true;nextPoll=0;NativeWorkspaceScreen.sessionReady();flushAwaiting();
            });return true;
        }
        var pending=PENDING.remove(packet.requestId());
        if(ready()&&packet.channel().equals("buildingChanged")){NativeBuildingPanel.changed(JsonParser.parseString(packet.json()).getAsJsonObject());return true;}
        if(pending!=null){if(packet.channel().equals("receipt"))pending.future.complete(JSON.fromJson(packet.json(),Receipt.class));else pending.future.completeExceptionally(new IllegalStateException("NATIVE_WORKSPACE_REQUEST_REJECTED"));return true;}
        if(ready()&&Set.of("conversationChanged","conversationVoiceStatus").contains(packet.channel())){NativeWorkspaceScreen.push(packet.channel(),JsonParser.parseString(packet.json()));return true;}
        return false;
    }
    public static CompletableFuture<Receipt> command(String action,Map<String,String> values,UUID operation){
        if(ready())return request("command",session,action,values,operation);
        var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null||mc.getConnection()==null)return CompletableFuture.failedFuture(new IllegalStateException(text("世界或玩家已变化，操作尚未发送。")));
        if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())return CompletableFuture.failedFuture(new IllegalStateException(text("请先在聊天中启用此世界的 DivZero。")));
        var old=AWAITING.get(operation);if(old!=null)return old.action.equals(action)&&old.values.equals(values)?old.future:CompletableFuture.failedFuture(new IllegalStateException("OPERATION_ID_REUSED"));
        var future=new CompletableFuture<Receipt>();AWAITING.put(operation,new Awaiting(action,Map.copyOf(values),operation,mc.getConnection(),mc.level,mc.player,System.currentTimeMillis()+15000,future));open();return future;
    }
    public static CompletableFuture<Receipt> contentRequest(String channel,Session target,String action,Map<String,String> values,UUID operation){
        if(!ready())return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));return request(channel,target,action,values,operation);
    }
    private static CompletableFuture<Receipt> request(String channel,Session target,String action,Map<String,String> values,UUID operation){
        var request=new Request(operation,target.sessionId(),target.pageGeneration(),target.controlEpoch(),target.binding().taskRevision(),action,values);
        var old=PENDING.get(operation);if(old!=null)return old.request.equals(request)&&old.channel.equals(channel)?old.future:CompletableFuture.failedFuture(new IllegalStateException("OPERATION_ID_REUSED"));
        var future=new CompletableFuture<Receipt>();PENDING.put(operation,new Pending(request,channel,future,System.currentTimeMillis()+15000));
        ClientPacketDistributor.sendToServer(new UiPayloads.Command(operation,channel,JSON.toJson(request)));return future;
    }
    public static void tick(){
        NativeInventoryPanel.tick();NativeBehaviorPanel.tick();NativeDeliveryPanel.tick();NativeGenerationPanel.tick();NativeBuildingPanel.tick();NativeWorkspaceModules.tick();NativeContentCatalogPanel.tick();NativeLifecyclePanel.tick();NativeCoderPanel.tick();NativeTasksPanel.tick();NativeDecisionPanel.tick();
        var mc=Minecraft.getInstance();long now=System.currentTimeMillis();flushAwaiting();
        if(connection!=null&&(connection!=mc.getConnection()||level!=mc.level)){reset();return;}
        for(var entry:List.copyOf(PENDING.entrySet()))if(now>=entry.getValue().deadline){PENDING.remove(entry.getKey());entry.getValue().future.completeExceptionally(new IllegalStateException("UI_OPERATION_TIMEOUT_OUTCOME_UNKNOWN"));}
        if(opening!=null&&now>=deadline){opening=null;failAwaiting(text("工作区连接超时，操作尚未发送。"));NativeWorkspaceScreen.notice(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("连接超时，请重新打开工作区"));}
        if(!ready()||polling||now<nextPoll)return;polling=true;nextPoll=now+1000;var expected=session;
        command("shell.read",Map.of("viewPage","0","decisionPage","0"),UUID.randomUUID()).whenComplete((receipt,error)->{
            if(session!=expected)return;polling=false;
            if(error!=null){NativeWorkspaceScreen.notice(error.getMessage());return;}
            if(Set.of(Code.EXPIRED,Code.STALE_VIEW,Code.VIEW_NOT_RENDERED).contains(receipt.code())){reset(false);open();return;}
            if(receipt.code()==Code.OBSERVED)NativeWorkspaceScreen.snapshot(receipt.values());else NativeWorkspaceScreen.notice(receipt.values().getOrDefault("errorCode",receipt.code().name()));
        });
    }
    public static void reset(){reset(true);}
    public static void activationChanged(boolean enabled){if(!enabled){NativePackageViews.clear();dev.mineagent.runtime.neoforge.client.webui.PackageContentClient.clear();if(Minecraft.getInstance().screen instanceof NativeWorkspaceScreen screen)screen.onClose();}reset(false);if(enabled){dev.mineagent.runtime.neoforge.client.webui.HudPersistenceClient.sessionReady(null);open();}}
    private static void reset(boolean contextChanged){failAwaiting(text("世界或玩家已变化，操作尚未发送。"));var copy=List.copyOf(PENDING.values());PENDING.clear();session=null;opening=null;connection=level=null;rendered=polling=false;if(contextChanged)NativeWorkspaceScreen.disconnected();for(var pending:copy)pending.future.completeExceptionally(new IllegalStateException("NATIVE_WORKSPACE_CONTEXT_CHANGED"));}
    private NativeWorkspaceConnection(){}
}
