package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.agent.ui.UiAgentController;
import dev.mineagent.runtime.api.ui.UiAgentRpc.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.UiAgentClientPolicy;
import dev.mineagent.runtime.client.webui.UiAgentRpcHistory;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.concurrent.*;

/** Executes only server-correlated Agent RPC in an explicitly rebound content frame. No provider credentials here. */
public final class UiAgentClient {
    private static final Gson JSON=new Gson();
    private static final class Control {final Session session;final UiAgentController.Port port;final UiAgentRpcHistory history=new UiAgentRpcHistory(128,8);final dev.mineagent.runtime.client.webui.UiCaptureSlot captures=new dev.mineagent.runtime.client.webui.UiCaptureSlot(java.time.Clock.systemUTC(),120000);Control(Session s,UiAgentController.Port p){session=s;port=p;}}
    private static final Map<String,Control> controls=new HashMap<>();
    private UiAgentClient(){}
    public static CompletableFuture<Receipt> delegate(String view,UUID agent,String goal,String expectedTitle,UUID operation){
        return delegate(view,agent,goal,expectedTitle,operation,false);
    }
    public static CompletableFuture<Receipt> delegate(String view,UUID agent,String goal,String expectedTitle,UUID operation,boolean presentationOnly){
        Session source=PackageContentClient.session(view);
        if(source==null||source.binding().actorKind()!=ActorKind.PLAYER)return CompletableFuture.failedFuture(new IllegalStateException("PLAYER_CONTENT_SESSION_REQUIRED"));
        return UiClientSessions.command("ui.delegate",Map.of("sourceSessionId",source.sessionId().toString(),"agentId",agent.toString(),"goal",goal,"expectedTitle",expectedTitle,"confirmed","true","presentationOnly",Boolean.toString(presentationOnly)),operation)
                .thenApply(receipt->{
                    if(receipt.code()!=Code.ACCEPTED)return receipt;
                    var target=JSON.fromJson(receipt.values().get("session"),Session.class);
                    if(!source.sessionId().toString().equals(receipt.values().get("sourceSessionId")))throw new SecurityException("STALE_DELEGATION_SOURCE");
                    UiAgentClientPolicy.requireRebind(source,target,agent);
                    var current=PackageContentClient.rawSession(view);if(current!=null&&current.sessionId().equals(target.sessionId()))return receipt;
                    try{PackageContentClient.rebind(source.sessionId(),target,agent);}
                    catch(RuntimeException unavailable){UiClientSessions.contentRequest("close",target,"scoreview.read",Map.of(),UUID.randomUUID());throw unavailable;}
                    return receipt;
                });
    }
    public static boolean accept(UiPayloads.Event packet){
        if(packet.channel().equals("uiAgentRpc")){
            try{var command=JSON.fromJson(packet.json(),Command.class);if(!command.requestId().equals(packet.requestId()))throw new IllegalArgumentException("UI_RPC_ID");execute(command);}
            catch(RuntimeException invalid){WebGuiHostAdapter.INSTANCE.emit("sessionError",Map.of("code","INVALID_UI_AGENT_RPC"));}return true;
        }
        if(packet.channel().equals("uiAgentStop")){
            var data=JsonParser.parseString(packet.json()).getAsJsonObject();String view=data.get("viewId").getAsString();
            var session=PackageContentClient.rawSession(view);
            if(session!=null&&session.sessionId().toString().equals(data.get("sessionId").getAsString()))stop(view,false);
            return true;
        }
        if(packet.channel().equals("uiAgentStatus")){WebGuiHostAdapter.INSTANCE.emit("uiAgentStatus",JsonParser.parseString(packet.json()));return true;}
        return false;
    }
    private static void execute(Command command){
        if(!WebGuiNativeInput.afterInputs(()->executeNow(command)))reply(command,"{\"status\":\"UI_INPUT_QUEUE_BUSY\"}");
    }
    private static void executeNow(Command command){
        Session session=PackageContentClient.session(command.viewId());
        if(!UiAgentClientPolicy.matches(session,command)){reply(command,"{\"status\":\"USER_INTERRUPTED\"}");return;}
        Control control=controls.get(command.viewId());
        if(control==null){
            var port=PackagePageAgent.controlDelegated(command.viewId(),session.sessionId(),command.kind().equals("presentationInspect"));
            control=new Control(session,port);controls.put(command.viewId(),control);
            port.onInterrupt(()->stop(command.viewId(),true,"PORT_CANCEL"));
        }
        if(!control.session.sessionId().equals(session.sessionId())){reply(command,"{\"status\":\"STALE_VIEW\"}");return;}
        final var active=control;
        try{
            CoordinateSmokePerturbation.before(command);
            var action=active.history.execute(command,()->switch(command.kind()){
                case "inspect" -> active.port.inspect();
                case "presentationInspect" -> active.port.inspectPresentation();
                case "act" -> active.port.act(command.actionJson());
                case "capture" -> active.port.capture().thenApply(image->{
                    if(controls.get(command.viewId())!=active||!UiAgentClientPolicy.matches(PackageContentClient.session(command.viewId()),command))throw new IllegalStateException("STALE_VIEW");
                    active.captures.publish(image);return JSON.toJson(Map.of("status","CAPTURED","capture",image.manifest()));
                });
                case "captureChunk" -> {
                    var data=JsonParser.parseString(command.actionJson()).getAsJsonObject();
                    var chunk=active.captures.chunk(UUID.fromString(data.get("captureId").getAsString()),data.get("index").getAsInt());
                    yield CompletableFuture.completedFuture(JSON.toJson(Map.of("status","CAPTURE_CHUNK","chunk",chunk)));
                }
                default -> throw new IllegalArgumentException("UI_RPC_ACTION");
            });
            action.whenComplete((r,e)->Minecraft.getInstance().execute(()->{
                String result=e==null?r:failureReceipt(e);reply(command,result);
            }));
        }catch(RuntimeException failed){reply(command,failureReceipt(failed));}
    }
    private static String failureReceipt(Throwable failure){
        for(int i=0;i<8&&failure.getCause()!=null;i++)failure=failure.getCause();
        String code=failure.getMessage();if(code==null||!Set.of("USER_INTERRUPTED","VIEW_NOT_RENDERED","STALE_VIEW","STALE_CAPTURE","VIEW_OCCLUDED","VIEW_POPUP_ACTIVE","CAPTURE_BUSY","SENSITIVE_VIEW","CAPTURE_PIXEL_BUDGET","OPERATION_ID_REUSED","LEDGER_FULL","UI_RPC_BUSY").contains(code))code="FAILED";
        return JSON.toJson(Map.of("status",code));
    }
    private static void reply(Command command,String value){
        if(Minecraft.getInstance().getConnection()==null)return;
        try{var reply=Reply.forCommand(command,value);ClientPacketDistributor.sendToServer(new UiPayloads.Command(command.requestId(),"uiAgentReply",JSON.toJson(reply)));}
        catch(RuntimeException invalid){WebGuiHostAdapter.INSTANCE.emit("sessionError",Map.of("code","UI_AGENT_REPLY_FAILED"));}
    }
    public static void stop(String view,boolean notify){
        stop(view,notify,"CLIENT_REQUEST");
    }
    public static void stop(String view,boolean notify,String signal){
        if(!Minecraft.getInstance().isSameThread()){Minecraft.getInstance().execute(()->stop(view,notify,signal));return;}
        Session session=PackageContentClient.rawSession(view);Control control=controls.remove(view);
        boolean wasActive=PackageContentClient.blockAgent(view);
        if(control!=null){control.captures.clear();control.history.close();control.port.cancel();}
        if(notify&&session!=null&&(wasActive||control!=null)&&Minecraft.getInstance().getConnection()!=null)
            ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"uiAgentInterrupt",JSON.toJson(Map.of("sessionId",session.sessionId(),"signal",dev.mineagent.runtime.api.ui.UiInterruptSignal.normalize(signal)))));
        if(session!=null&&session.binding().actorKind()==ActorKind.AGENT)WebGuiHostAdapter.INSTANCE.emit("uiAgentStopped",Map.of("viewId",view,"status",dev.mineagent.runtime.api.ui.ContainerProtocol.bound(session.binding())?"Agent 容器任务已停止；光标物品按 Agent 自身原生规则归还，不转为玩家权限。":dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(session.binding())?"页面委派已停止，当前本地页面仍保留；重新打开新页面后才能再次委派，未授予世界写权限。":"已停止：保留草稿；此文档不可写，重新打开玩家视图可继续。"));
    }
    public static void interruptAll(){for(String view:PackageContentClient.agentViews())stop(view,true,"ALL_VIEWS_INTERRUPTED");}
    public static void clear(){for(String view:List.copyOf(controls.keySet()))stop(view,false);controls.clear();}
}
