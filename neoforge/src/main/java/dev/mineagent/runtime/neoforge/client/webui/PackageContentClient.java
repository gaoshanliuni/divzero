package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.PackageFrameGate;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Named CEF bridge bound to actual loaded package frames. The page supplies data/actions, never its authority. */
public final class PackageContentClient {
    private static final Gson JSON=new Gson();
    private static final PackageFrameGate GATE=new PackageFrameGate();
    private static final Map<String,View> views=new HashMap<>();
    private static CefMessageRouter router;
    private static long rateWindow; private static int rateCount;
    private static final class View {
        Session session; final String url; long load; int readmissionAttempts; boolean initialized,ready,blocked,visible=true,skipSdkOnce,humanEdited,reopening;
        View(Session session,String url){this.session=session;this.url=url;}
    }
    private PackageContentClient() {}
    public static void register() {
        if(router!=null)return;
        router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentContentQuery","mineagentContentQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback) {
                if(frame==null || request==null || persistent || !WebGuiHostAdapter.INSTANCE.owns(browser) || !allowMessage()) {callback.failure(403,"CONTENT_SOURCE_REJECTED");return true;}
                long frameId=frame.getIdentifier();String url=frame.getURL();
                var admitted=GATE.accept(browser,frameId,frame.isMain(),url,request.length());
                if(admitted.isEmpty()){callback.failure(403,"CONTENT_SOURCE_REJECTED");return true;}
                Session session=admitted.get();
                Minecraft.getInstance().execute(()->{
                    Runnable dispatch=()->{
                    if(GATE.accept(browser,frameId,false,url,request.length()).filter(s->dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(s,session)).isEmpty()){callback.failure(409,"STALE_VIEW");return;}
                    try {
                        var message=JsonParser.parseString(request).getAsJsonObject();
                        String action=message.get("action").getAsString();
                        if(!Set.of("scoreview.read","scoreview.patch","container.read","container.act","worldui.read","worldui.action","delivery.read","delivery.dataRead","feedback.submit","feedback.read","feedback.stateRead").contains(action))throw new IllegalArgumentException("CONTENT_ACTION");
                        if(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")&&action.equals("scoreview.read")&&ScoreHudSmokeClient.pauseRead(session.binding().viewId())){callback.failure(408,"FIXTURE_READ_PAUSED");return;}
                        UUID operation=UUID.fromString(message.get("operationId").getAsString());
                        var arguments=new LinkedHashMap<String,String>();
                        var values=message.getAsJsonObject("arguments");
                        if(values.size()>32)throw new IllegalArgumentException("CONTENT_ARGUMENTS");
                        values.entrySet().forEach(e->{
                            if(!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("CONTENT_ARGUMENT_TYPE");
                            arguments.put(e.getKey(),e.getValue().getAsString());
                        });
                        var pushRead=action.equals("worldui.read")?StatePushClient.prepare(session,arguments):null;Map<String,String> dispatchedArguments=pushRead==null?arguments:pushRead.arguments();
                        (action.equals("delivery.dataRead")?ContentDeliveryClient.acknowledgeData(session.binding().viewId(),session,arguments,operation):action.equals("feedback.submit")?ContentDeliveryClient.submitFeedback(session.binding().viewId(),session,arguments,operation):UiClientSessions.contentRequest("command",session,action,dispatchedArguments,operation)).whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{
                            // The CefFrame passed to onQuery is callback-scoped; reacquire its native handle after async work.
                            var current=browser.getFrame(frameId);
                            if(current==null || GATE.accept(browser,frameId,current.isMain(),current.getURL(),request.length()).filter(s->dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(s,session)).isEmpty()){StatePushClient.failed(session,pushRead);callback.failure(409,"STALE_VIEW");}
                            else if(error!=null){StatePushClient.failed(session,pushRead);callback.failure(400,"CONTENT_OPERATION_FAILED");}
                            else try{
                                if(receipt.code()==Code.OBSERVED&&receipt.values().containsKey("renewedSession")){
                                    Session offered=dev.mineagent.runtime.api.ui.ReadOnlyUiLease.accept(session,JSON.fromJson(receipt.values().get("renewedSession"),Session.class));
                                    var view=views.get(session.binding().viewId());
                                    if(view==null||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(view.session,offered))throw new SecurityException("UI_LEASE_CONTEXT");
                                    // Concurrent successful read replies may arrive out of order. Never shorten the latest known lease.
                                    if(offered.expiresAtMillis()>view.session.expiresAtMillis()){
                                        view.session=offered;GATE.bind(browser,frameId,url,offered);
                                    }
                                }
                                var publicReceipt=pushRead==null?receipt:StatePushClient.publicReceipt(pushRead,receipt);callback.success(JSON.toJson(action.equals("delivery.read")?ContentDeliveryClient.witnessRead(session,publicReceipt):publicReceipt));if(pushRead!=null)StatePushClient.received(session,pushRead,receipt);
                                if(dev.mineagent.runtime.api.ui.ReadOnlyUiLease.lostLease(receipt.code())){
                                    var view=views.get(session.binding().viewId());var descriptor=WebGuiHostAdapter.INSTANCE.viewPackage(session.binding().viewId());
                                    if(view!=null&&descriptor!=null&&descriptor.passive()&&view.visible&&!view.blocked&&!view.reopening){
                                        view.ready=false;GATE.close(session.binding().viewId());readmitHud(session.binding().viewId(),view,++view.load);
                                    }
                                }
                            }catch(RuntimeException invalid){StatePushClient.failed(session,pushRead);callback.failure(409,"CONTENT_LEASE_CONTEXT");}
                        }));
                    }catch(RuntimeException invalid){callback.failure(400,"INVALID_CONTENT_REQUEST");}
                    };
                    if(session.binding().actorKind()==ActorKind.AGENT){if(!WebGuiNativeInput.afterInputs(dispatch))callback.failure(429,"UI_INPUT_QUEUE_BUSY");}
                    else dispatch.run();
                });return true;
            }
        },true);
        MCEF.getClient().getHandle().addMessageRouter(router);
    }
    public static void mount(Session session,String url) {
        PackageUiStateClient.invalidate(session.binding().viewId());
        if(views.size()>=32)throw new IllegalStateException("CONTENT_VIEW_BUDGET");
        if(views.putIfAbsent(session.binding().viewId(),new View(session,url))!=null)throw new IllegalStateException("CONTENT_VIEW_EXISTS");
    }
    public static void loaded(String viewId) {
        StatePushClient.close(viewId);
        View view=views.get(viewId);if(view==null)return;
        ContentTakeoverClient.reloading(viewId);
        if(view.initialized&&view.session.binding().actorKind()==ActorKind.AGENT){UiAgentClient.stop(viewId,true,"FRAME_NAVIGATION");return;}
        view.ready=false;
        GATE.close(viewId);PackagePageAgent.cancel(viewId);long load=++view.load;
        if(view.initialized) {
            UiClientSessions.contentRequest("navigateContent",view.session,"scoreview.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->{
                if(views.get(viewId)!=view||load!=view.load)return;
                if(error!=null||receipt.code()!=Code.ACCEPTED){diagnostic(viewId,"CONTENT_NAVIGATION_FAILED");return;}
                try{view.session=JSON.fromJson(receipt.values().get("session"),Session.class);render(viewId,view,load);}
                catch(RuntimeException invalid){diagnostic(viewId,"CONTENT_SESSION_INVALID");}
            });
        }else{view.initialized=true;render(viewId,view,load);}
    }
    private static void render(String viewId,View view,long load) {
        CefFrame frame=findFrame(view.url);if(frame==null){diagnostic(viewId,"VIEW_NOT_RENDERED");return;}
        UiClientSessions.contentRequest("rendered",view.session,"scoreview.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->{
            if(views.get(viewId)!=view||load!=view.load||view.blocked||!view.visible)return;
            var current=findFrame(view.url);
            if(error!=null||receipt.code()!=Code.OK||current==null){
                if(error==null&&dev.mineagent.runtime.api.ui.ReadOnlyUiLease.lostLease(receipt.code())&&current!=null&&readmitHud(viewId,view,load))return;
                diagnostic(viewId,"CONTENT_RENDER_ADMISSION_FAILED");return;
            }
            var old=view.session;view.session=new Session(old.sessionId(),old.serverInstanceId(),old.binding(),old.pageGeneration(),old.controlEpoch(),old.expiresAtMillis(),Status.RENDERED);
            view.ready=true;view.readmissionAttempts=0;GATE.bind(WebGuiHostAdapter.INSTANCE.browser(),current.getIdentifier(),view.url,view.session);
            try(var stream=PackageContentClient.class.getResourceAsStream("/assets/mineagent_runtime/webui/"+(dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(view.session.binding())?"delivery-sdk.js":dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(view.session.binding())?"world-ui-sdk.js":dev.mineagent.runtime.api.ui.ContainerProtocol.bound(view.session.binding())?"container-sdk.js":"content-sdk.js"))){
                if(stream==null)throw new IllegalStateException("CONTENT_SDK_MISSING");
                boolean pageOnly=dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(view.session.binding());
                if(!view.skipSdkOnce&&!pageOnly)current.executeJavaScript(new String(stream.readAllBytes(),StandardCharsets.UTF_8),view.url,0);
                if(dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(view.session.binding())&&dev.mineagent.runtime.api.ui.DeliveryProtocol.feedbackEnabled(view.session.binding()))try(var sdk=PackageContentClient.class.getResourceAsStream("/assets/mineagent_runtime/webui/feedback-sdk.js")){if(sdk==null)throw new IllegalStateException("FEEDBACK_SDK_MISSING");current.executeJavaScript(new String(sdk.readAllBytes(),StandardCharsets.UTF_8),view.url,0);}
                view.skipSdkOnce=false;
                PageControlClient.rendered(viewId);
                WebGuiHostAdapter.INSTANCE.emit("contentReady",Map.of("viewId",viewId,"rendered",true,"actorKind",view.session.binding().actorKind().name(),"capabilities",view.session.binding().capabilities(),"pageOnly",pageOnly));
                ContentTakeoverClient.ready(viewId);
                HudPersistenceClient.ready(viewId);
                ContentDeliveryClient.rendered(viewId);
            }catch(Exception failed){GATE.close(viewId);diagnostic(viewId,"CONTENT_SDK_FAILED");}
        });
    }
    private static boolean readmitHud(String id,View view,long load){
        var descriptor=WebGuiHostAdapter.INSTANCE.viewPackage(id);var old=view.session;
        if(descriptor==null||!descriptor.passive()||view.reopening||view.readmissionAttempts>=1||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.eligible(old.binding()))return false;
        view.reopening=true;view.readmissionAttempts++;
        UiClientSessions.command("package.hudLease",Map.of("viewId",id,"packageId",descriptor.packageId().toString(),"packageRevision",Long.toString(descriptor.revision()),
                "targetViewId",old.binding().targetObjectId()),UUID.randomUUID()).whenComplete((receipt,error)->{
            Session fresh=null;
            try{
                if(error!=null||receipt.code()!=Code.APPLIED)throw new IllegalStateException("HUD_READMISSION_FAILED");
                fresh=dev.mineagent.runtime.api.ui.ReadOnlyUiLease.readmitted(old,JSON.fromJson(receipt.values().get("session"),Session.class));
                if(views.get(id)!=view||view.load!=load||view.blocked||!view.visible||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(old,view.session)){
                    UiClientSessions.contentRequest("close",fresh,"scoreview.read",Map.of(),UUID.randomUUID());return;
                }
                view.session=fresh;view.skipSdkOnce=true;view.ready=false;render(id,view,++view.load);
            }catch(RuntimeException failure){
                if(fresh!=null)UiClientSessions.contentRequest("close",fresh,"scoreview.read",Map.of(),UUID.randomUUID());
                if(views.get(id)==view&&view.load==load)diagnostic(id,"HUD_READMISSION_FAILED");
            }finally{view.reopening=false;}
        });return true;
    }
    public static void close(String viewId) {
        StatePushClient.close(viewId);
        PackageUiStateClient.invalidate(viewId);
        ContentHotSwapClient.closed(viewId);
        ContentTakeoverClient.close(viewId);
        UiAgentClient.stop(viewId,true,"VIEW_CLOSED");
        GATE.close(viewId);var view=views.remove(viewId);
        if(view!=null){ContentDeliveryClient.closed(viewId,view.session);UiClientSessions.contentRequest("close",view.session,"scoreview.read",Map.of(),UUID.randomUUID());}
    }
    public static void clear() { StatePushClient.clear(); for(var v:List.copyOf(views.values()))if(dev.mineagent.runtime.api.ui.ContainerProtocol.bound(v.session.binding())||dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(v.session.binding()))UiClientSessions.contentRequest("close",v.session,"container.read",Map.of(),UUID.randomUUID());PageControlClient.clear();ContentHotSwapClient.clear();ContentTakeoverClient.clear();UiAgentClient.clear();GATE.clear();views.clear(); }
    public static boolean owns(String viewId) { return views.containsKey(viewId); }
    public static void outdated(String viewId,UUID sessionId){
        var view=views.get(viewId);if(view==null||!view.session.sessionId().equals(sessionId))return;
        PackageUiStateClient.invalidate(viewId);
        view.blocked=true;GATE.close(viewId);UiAgentClient.stop(viewId,false);PackagePageAgent.cancel(viewId);
        if(view.session.binding().preview()&&!dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(view.session.binding())&&!view.humanEdited&&!ContentTakeoverClient.hasHumanEdits(viewId)){WebGuiHostAdapter.INSTANCE.emit("closeManagedView",Map.of("viewId",viewId));return;}
        WebGuiHostAdapter.INSTANCE.emit("packageViewOutdated",Map.of("viewId",viewId));
    }
    public static void humanInput(String id){var view=views.get(id);if(view!=null)view.humanEdited=true;}
    public static Session rawSession(String id){var v=views.get(id);return v==null?null:v.session;}
    public static long lifecycle(String id){var view=views.get(id);return view==null?-1:view.load;}
    public static List<Session> transitionSources(UUID pkg,long revision){return views.values().stream().filter(v->v.visible&&v.ready&&!v.blocked&&!v.session.binding().preview()&&!dev.mineagent.runtime.api.ui.ContainerProtocol.bound(v.session.binding())&&!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(v.session.binding())&&!dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(v.session.binding())&&(WebGuiHostAdapter.INSTANCE.viewPackage(v.session.binding().viewId())==null||!WebGuiHostAdapter.INSTANCE.viewPackage(v.session.binding().viewId()).passive())&&v.session.binding().ownerPackageId().equals(pkg)&&v.session.binding().packageRevision()==revision).map(v->v.session).sorted(Comparator.comparing(s->s.binding().viewId())).toList();}
    public static Session session(String id){var v=views.get(id);return v==null||v.blocked||!v.ready||!v.visible?null:v.session;}
    public static List<String> worldViews(){return views.entrySet().stream().filter(e->dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(e.getValue().session.binding())).map(Map.Entry::getKey).toList();}
    public static List<String> agentViews(){return views.entrySet().stream().filter(e->e.getValue().session.binding().actorKind()==ActorKind.AGENT&&!e.getValue().blocked).map(Map.Entry::getKey).toList();}
    public static boolean blockAgent(String id){var view=views.get(id);if(view==null||view.blocked||view.session.binding().actorKind()!=ActorKind.AGENT)return false;
        PackageUiStateClient.invalidate(id);
        view.blocked=true;view.ready=false;GATE.close(id);return true;}
    public static void visibility(String id,boolean visible){
        var view=views.get(id);if(view==null||view.visible==visible)return;view.visible=visible;if(!visible)StatePushClient.close(id);if(dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(view.session.binding()))ContentDeliveryClient.visibility(id,visible);
        if(!visible&&(dev.mineagent.runtime.api.ui.ContainerProtocol.bound(view.session.binding())||dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(view.session.binding()))){
            view.blocked=true;view.ready=false;++view.load;GATE.close(id);PackageUiStateClient.invalidate(id);PackagePageAgent.cancel(id);
            UiClientSessions.contentRequest("close",view.session,"container.read",Map.of(),UUID.randomUUID());diagnostic(id,"CONTAINER_CLOSED_REOPEN_EXPLICITLY");return;
        }
        if(!visible)PackageUiStateClient.invalidate(id);
        if(!visible){view.ready=false;++view.load;GATE.close(id);UiAgentClient.stop(id,true,"VIEW_HIDDEN");PackagePageAgent.cancel(id);}
        else if(view.initialized&&!view.blocked)render(id,view,++view.load);
        if(!visible)WebGuiHostAdapter.INSTANCE.emit("contentReady",Map.of("viewId",id,"rendered",false,"actorKind",view.session.binding().actorKind().name(),"capabilities",view.session.binding().capabilities()));
    }
    public static void rebind(UUID sourceId,Session target,UUID expectedAgent){
        String id=target.binding().viewId();var view=views.get(id);
        if(view==null||!view.session.sessionId().equals(sourceId)||view.blocked||!view.ready)throw new SecurityException("STALE_DELEGATION_SOURCE");
        dev.mineagent.runtime.client.webui.UiAgentClientPolicy.requireRebind(view.session,target,expectedAgent,view.visible);
        PackageUiStateClient.invalidate(id);
        GATE.close(id);PackagePageAgent.cancel(id);view.ready=false;view.session=target;long load=++view.load;render(id,view,load);
    }
    public static void freezeForTakeover(String id){
        var view=views.get(id);if(view==null)throw new IllegalArgumentException("VIEW_NOT_RENDERED");
        PackageUiStateClient.invalidate(id);
        UiAgentClient.stop(id,true,"TAKEOVER");PackagePageAgent.cancel(id);GATE.close(id);view.blocked=true;view.ready=false;++view.load;
        UiClientSessions.contentRequest("close",view.session,"scoreview.read",Map.of(),UUID.randomUUID());
        WebGuiHostAdapter.INSTANCE.emit("contentReady",Map.of("viewId",id,"rendered",false,"actorKind",view.session.binding().actorKind().name(),"capabilities",view.session.binding().capabilities()));
    }
    public static void activateTakeover(Session source,Session target){
        var a=source.binding();var b=target.binding();var view=views.get(a.viewId());
        if(view==null||!view.session.sessionId().equals(source.sessionId())||!view.ready||view.blocked||!view.visible
                ||source.sessionId().equals(target.sessionId())||!source.serverInstanceId().equals(target.serverInstanceId())||!a.viewId().equals(b.viewId())
                ||!dev.mineagent.runtime.client.webui.ContentDraftScope.of("",a).equals(dev.mineagent.runtime.client.webui.ContentDraftScope.of("",b))
                ||a.actorKind()!=ActorKind.PLAYER||b.actorKind()!=ActorKind.PLAYER||!b.actorId().equals(b.viewerPlayerId())||b.taskId()!=null
                ||!a.capabilities().equals(Set.of("scoreview.read"))||!b.capabilities().equals(Set.of("scoreview.read","scoreview.patch")))throw new SecurityException("TAKEOVER_ACTIVATION_CONTEXT");
        PackageUiStateClient.invalidate(a.viewId());GATE.close(a.viewId());view.session=target;view.ready=false;view.skipSdkOnce=true;render(a.viewId(),view,++view.load);
    }
    public static boolean statePushFrame(CefBrowser browser,long frameId,String url,Session expected){
        var view=views.get(expected.binding().viewId());return view!=null&&view.ready&&view.visible&&!view.blocked&&dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(view.session,expected)&&GATE.accept(browser,frameId,false,url,0).filter(current->dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(current,expected)).isPresent();
    }
    private static CefFrame findFrame(String url) {
        var browser=WebGuiHostAdapter.INSTANCE.browser();if(browser==null)return null;
        for(long id:browser.getFrameIdentifiers()){var frame=browser.getFrame(id);if(frame!=null&&!frame.isMain()&&url.equals(frame.getURL()))return frame;}return null;
    }
    private static synchronized boolean allowMessage(){long now=System.currentTimeMillis();if(now-rateWindow>=1000){rateWindow=now;rateCount=0;}return ++rateCount<=60;}
    private static void diagnostic(String id,String code){WebGuiHostAdapter.INSTANCE.emit("contentError",Map.of("viewId",id,"code",code));HudPersistenceClient.failed(id);}
}
