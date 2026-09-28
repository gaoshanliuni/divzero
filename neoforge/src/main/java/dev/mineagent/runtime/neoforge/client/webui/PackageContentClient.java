package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.ReadOnlyUiLease;
import dev.mineagent.runtime.neoforge.client.nativeui.NativePackageViews;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Scoped native-package RPC. Widget definitions never supply or increase session authority. */
public final class PackageContentClient {
    private static final Gson JSON=new Gson();
    private static final Map<String,View> views=new HashMap<>();
    private static long rateWindow;private static int rateCount;
    private static final class View {
        Session session;long load;int readmissionAttempts;boolean initialized,ready,blocked,visible=true,skipSdkOnce,humanEdited,reopening,admitting;
        View(Session session){this.session=session;}
    }
    private PackageContentClient(){}
    public static void register(){}
    public static void mount(Session session,String unused){
        PackageUiStateClient.invalidate(session.binding().viewId());
        if(views.size()>=32)throw new IllegalStateException("CONTENT_VIEW_BUDGET");
        if(views.putIfAbsent(session.binding().viewId(),new View(session))!=null)throw new IllegalStateException("CONTENT_VIEW_EXISTS");
    }
    public static CompletableFuture<Receipt> request(String id,Session source,String action,Map<String,String> arguments,UUID operation){
        if(action.startsWith("state."))return PackageUiStateClient.request(id,action,arguments,operation);
        var result=new CompletableFuture<Receipt>();
        try{
            var view=views.get(id);if(view==null||!view.ready||view.blocked||!view.visible||!ReadOnlyUiLease.sameContext(source,view.session)||!allowMessage())throw new SecurityException("CONTENT_SOURCE_REJECTED");
            var current=view.session;long load=view.load;
            var push=action.equals("worldui.read")?StatePushClient.prepare(current,arguments):null;
            var requested=push==null?arguments:push.arguments();
            var pending=action.equals("delivery.dataRead")?ContentDeliveryClient.acknowledgeData(id,current,arguments,operation):action.equals("feedback.submit")?ContentDeliveryClient.submitFeedback(id,current,arguments,operation):UiClientSessions.contentRequest("command",current,action,requested,operation);
            pending.whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{
                try{
                    if(views.get(id)!=view||view.load!=load||view.blocked||!ReadOnlyUiLease.sameContext(view.session,current))throw new SecurityException("STALE_VIEW");
                    if(error!=null)throw new CompletionException(error);
                    if(receipt.code()==Code.OBSERVED&&receipt.values().containsKey("renewedSession")){
                        var offered=ReadOnlyUiLease.accept(current,JSON.fromJson(receipt.values().get("renewedSession"),Session.class));
                        if(offered.expiresAtMillis()>view.session.expiresAtMillis())setSession(id,view,offered);
                    }
                    var publicReceipt=push==null?receipt:StatePushClient.publicReceipt(push,receipt);
                    // Never hand the renewable authority token to declarative UI data.
                    var values=new LinkedHashMap<>(publicReceipt.values());values.remove("renewedSession");publicReceipt=new Receipt(publicReceipt.operationId(),publicReceipt.code(),values);
                    if(action.equals("delivery.read"))publicReceipt=ContentDeliveryClient.witnessRead(current,publicReceipt);
                    result.complete(publicReceipt);if(push!=null)StatePushClient.received(current,push,receipt);
                    if(ReadOnlyUiLease.lostLease(receipt.code())&&view.visible&&!view.blocked&&!view.reopening){view.ready=false;readmitHud(id,view,++view.load);}
                }catch(Exception failed){StatePushClient.failed(current,push);result.completeExceptionally(failed);}
            }));
        }catch(Exception failed){result.completeExceptionally(failed);}return result;
    }
    private static void setSession(String id,View view,Session target){var source=NativePackageViews.rawSession(id);if(source!=null)NativePackageViews.rebind(id,source,target);view.session=target;}
    public static void loaded(String id){var view=views.get(id);if(view==null||view.blocked||view.admitting)return;if(!view.initialized){view.initialized=true;++view.load;}render(id,view,view.load);}
    private static void render(String id,View view,long load){
        if(!NativePackageViews.painted(id)||view.admitting||view.blocked||!view.visible)return;
        view.admitting=true;
        UiClientSessions.contentRequest("rendered",view.session,"scoreview.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{
            view.admitting=false;if(views.get(id)!=view||load!=view.load||view.blocked||!view.visible)return;
            if(error!=null||receipt.code()!=Code.OK){if(error==null&&ReadOnlyUiLease.lostLease(receipt.code())&&readmitHud(id,view,load))return;diagnostic(id,"CONTENT_RENDER_ADMISSION_FAILED");return;}
            var old=view.session;setSession(id,view,new Session(old.sessionId(),old.serverInstanceId(),old.binding(),old.pageGeneration(),old.controlEpoch(),old.expiresAtMillis(),Status.RENDERED));
            view.ready=true;view.readmissionAttempts=0;NativePackageViews.admitted(id);
            PageControlClient.rendered(id);ContentTakeoverClient.ready(id);HudPersistenceClient.ready(id);ContentDeliveryClient.rendered(id);
        }));
    }
    public static void tick(){for(var entry:List.copyOf(views.entrySet())){var v=entry.getValue();if(!v.ready&&!v.blocked&&v.visible&&!v.admitting&&!v.reopening&&NativePackageViews.painted(entry.getKey()))loaded(entry.getKey());}}
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
                setSession(id,view,fresh);view.skipSdkOnce=true;view.ready=false;render(id,view,++view.load);
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
        var view=views.remove(viewId);
        if(view!=null){ContentDeliveryClient.closed(viewId,view.session);UiClientSessions.contentRequest("close",view.session,"scoreview.read",Map.of(),UUID.randomUUID());}
    }
    public static void clear() { StatePushClient.clear(); for(var v:List.copyOf(views.values()))if(dev.mineagent.runtime.api.ui.ContainerProtocol.bound(v.session.binding())||dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(v.session.binding()))UiClientSessions.contentRequest("close",v.session,"container.read",Map.of(),UUID.randomUUID());PageControlClient.clear();ContentHotSwapClient.clear();ContentTakeoverClient.clear();UiAgentClient.clear();views.clear(); }
    public static boolean owns(String viewId) { return views.containsKey(viewId); }
    public static void outdated(String viewId,UUID sessionId){
        var view=views.get(viewId);if(view==null||!view.session.sessionId().equals(sessionId))return;
        PackageUiStateClient.invalidate(viewId);
        view.blocked=true;UiAgentClient.stop(viewId,false);PackagePageAgent.cancel(viewId);
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
        view.blocked=true;view.ready=false;NativePackageViews.block(id,"AGENT_STOPPED");return true;}
    public static void visibility(String id,boolean visible){
        var view=views.get(id);if(view==null||view.visible==visible)return;view.visible=visible;if(!visible)StatePushClient.close(id);if(dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(view.session.binding()))ContentDeliveryClient.visibility(id,visible);
        if(!visible&&(dev.mineagent.runtime.api.ui.ContainerProtocol.bound(view.session.binding())||dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(view.session.binding()))){
            view.blocked=true;view.ready=false;++view.load;PackageUiStateClient.invalidate(id);PackagePageAgent.cancel(id);
            UiClientSessions.contentRequest("close",view.session,"container.read",Map.of(),UUID.randomUUID());diagnostic(id,"CONTAINER_CLOSED_REOPEN_EXPLICITLY");return;
        }
        if(!visible)PackageUiStateClient.invalidate(id);
        if(!visible){view.ready=false;++view.load;UiAgentClient.stop(id,true,"VIEW_HIDDEN");PackagePageAgent.cancel(id);}
        else if(view.initialized&&!view.blocked)render(id,view,++view.load);
        if(!visible)WebGuiHostAdapter.INSTANCE.emit("contentReady",Map.of("viewId",id,"rendered",false,"actorKind",view.session.binding().actorKind().name(),"capabilities",view.session.binding().capabilities()));
    }
    public static void rebind(UUID sourceId,Session target,UUID expectedAgent){
        String id=target.binding().viewId();var view=views.get(id);
        if(view==null||!view.session.sessionId().equals(sourceId)||view.blocked||!view.ready)throw new SecurityException("STALE_DELEGATION_SOURCE");
        dev.mineagent.runtime.client.webui.UiAgentClientPolicy.requireRebind(view.session,target,expectedAgent,view.visible);
        PackageUiStateClient.invalidate(id);
        PackagePageAgent.cancel(id);view.ready=false;setSession(id,view,target);long load=++view.load;render(id,view,load);
    }
    public static void freezeForTakeover(String id){
        var view=views.get(id);if(view==null)throw new IllegalArgumentException("VIEW_NOT_RENDERED");
        PackageUiStateClient.invalidate(id);
        UiAgentClient.stop(id,true,"TAKEOVER");PackagePageAgent.cancel(id);view.blocked=true;view.ready=false;++view.load;
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
        PackageUiStateClient.invalidate(a.viewId());setSession(a.viewId(),view,target);view.ready=false;view.skipSdkOnce=true;render(a.viewId(),view,++view.load);
    }
    private static synchronized boolean allowMessage(){long now=System.currentTimeMillis();if(now-rateWindow>=1000){rateWindow=now;rateCount=0;}return ++rateCount<=60;}
    private static void diagnostic(String id,String code){var view=views.get(id);if(view!=null){view.blocked=true;view.ready=false;}NativePackageViews.block(id,code);WebGuiHostAdapter.INSTANCE.emit("contentError",Map.of("viewId",id,"code",code));HudPersistenceClient.failed(id);}
}
