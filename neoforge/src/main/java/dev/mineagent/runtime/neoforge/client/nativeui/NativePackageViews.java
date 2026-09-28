package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.PackagePreviewTransfer;
import dev.mineagent.runtime.core.ui.dynamic.*;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import dev.mineagent.runtime.neoforge.client.webui.PackageContentClient;

/** Native signed-package view host. One data/authority session, no browser or DOM runtime. */
public final class NativePackageViews {
    public interface Transport {CompletableFuture<Receipt> request(String view,Session session,String action,Map<String,String> args,UUID operation);}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,View> VIEWS=new LinkedHashMap<>();
    private static Object connection,level,player;private static long tick;
    private static final class View {
        final String id,document=UUID.randomUUID().toString();final PackagePreviewTransfer.Resolved asset;final InterfaceSession<LdInterfaceRenderer.Rendered> content;
        NativePackageDefinition definition;NativePackageResources resources;Session session;Transport transport;WorkspaceWindow window;NativeWorkspaceScreen host;HudScreen projection;boolean ready,blocked,closed,visible=true,observedVisibility;long paintedTick=-1,lifecycle=1;
        String hostDocument=UUID.randomUUID().toString(),layoutSignature="",layoutSource="PLAYER";long layoutRevision=1,layoutSaveAt,presentAfter,presentDeadline;double opacity=1;boolean savingPresentation;CompletableFuture<com.google.gson.JsonObject> presentation;dev.mineagent.runtime.core.ui.UiPresentationAction presentAction;boolean agentDispatch;Map<String,Object> sealedDraft;final Set<String> refreshing=new HashSet<>();final Map<String,Long> nextRead=new HashMap<>();final Set<String> reading=new HashSet<>();final Map<String,UUID> writes=new HashMap<>();String error="";
        View(String id,PackagePreviewTransfer.Resolved asset,Session session,NativePackageDefinition definition,Transport transport){
            this.id=id;this.asset=asset;this.session=session;this.definition=definition;this.transport=transport;
            var mc=Minecraft.getInstance();var world=session==null?NativeWorkspaceConnection.current().binding().worldId():session.binding().worldId();
            content=new InterfaceSession<>(new InterfaceSession.Scope(world,mc.player.getUUID(),asset.runtimePackage().packageId(),UUID.randomUUID(),definition.view().id()));
        }
    }
    public static String open(PackagePreviewTransfer.Resolved asset,Session session,boolean passive,Transport transport)throws Exception {
        thread();context();var mc=Minecraft.getInstance();if(mc.player==null||!NativeWorkspaceConnection.ready())throw new IllegalStateException("VIEW_NOT_RENDERED");
        if(session!=null&&(!session.serverInstanceId().equals(NativeWorkspaceConnection.current().serverInstanceId())||!session.binding().worldId().equals(NativeWorkspaceConnection.current().binding().worldId())||!session.binding().viewerPlayerId().equals(mc.player.getUUID())||!session.binding().ownerPackageId().equals(asset.runtimePackage().packageId())||session.binding().packageRevision()!=asset.runtimePackage().revision()||!session.binding().entryPath().equals(asset.entry())))throw new SecurityException("NATIVE_PACKAGE_BINDING");
        var entry=asset.assets().get(asset.entry());if(entry==null)throw new IllegalArgumentException("UI_ENTRYPOINT_MISSING");
        if(!asset.entry().endsWith(".json"))throw new IllegalArgumentException("LEGACY_UI_REWRITE_REQUIRED: "+asset.entry());
        var definition=NativePackageDefinition.parse(new String(entry.bytes(),StandardCharsets.UTF_8));
        if(passive!=(definition.view().surface()==InterfaceDefinition.Surface.HUD))throw new IllegalArgumentException("NATIVE_PACKAGE_SURFACE");
        String id=session==null?"preview-"+UUID.randomUUID():session.binding().viewId();
        if(VIEWS.containsKey(id)){var old=VIEWS.get(id);if(!old.asset.runtimePackage().canonicalSha256().equals(asset.runtimePackage().canonicalSha256())||!Objects.equals(old.session,session))throw new SecurityException("NATIVE_PACKAGE_VIEW_EXISTS");show(id);return id;}
        if(VIEWS.size()>=32)throw new IllegalStateException("CONTENT_VIEW_BUDGET");
        var view=new View(id,asset,session,definition,transport);var requested=new HashSet<String>();resources(definition.view().root(),requested);
        view.resources=new NativePackageResources(asset.assets(),requested);
        try{
            var receipt=view.content.replace(view.content.scope(),0,JSON.writeValueAsString(definition.view()),(d,data)->KubeInterfaceRenderer.build(d,data,(node,event,value)->event(view,node,event,value),view.resources.textures(),!passive));
            if(!receipt.applied())throw new IllegalArgumentException(receipt.error());paintWitness(view);
            VIEWS.put(id,view);
            if(passive){view.content.interactive(false);LdHudRegistry.attach(view.content,definition.view().order());}
            else mount(view);
            if(session==null)view.ready=true;NativePackagePlacement.restore(id);
            return id;
        }catch(Exception failure){VIEWS.remove(id,view);view.content.close();view.resources.close();throw failure;}
    }
    private static void resources(JsonNode node,Set<String> values){if(node.has("resource"))values.add(node.get("resource").asText());for(var child:node.path("children"))resources(child,values);}
    private static void mount(View view){mount(view,NativeWorkspaceScreen.previewHost());}
    private static void mount(View view,NativeWorkspaceScreen host){
        if(view.host!=null&&view.host!=host){var receipt=view.content.remount((d,data)->KubeInterfaceRenderer.build(d,data,(node,event,value)->event(view,node,event,value),view.resources.textures(),true));if(!receipt.applied())throw new IllegalStateException(receipt.error());paintWitness(view);}
        view.hostDocument=UUID.randomUUID().toString();view.host=host;view.window=host.window("native-package-"+view.id,view.definition.view().title(),480,340);view.window.body.clearAllChildren();view.content.rendered().root.getLayout().widthPercent(100).heightPercent(100);view.window.body.addChild(view.content.rendered().root);view.content.interactive(!view.blocked);view.paintedTick=-1;
    }
    public static void workspaceOpened(NativeWorkspaceScreen host){for(var view:List.copyOf(VIEWS.values()))if(view.host!=null&&view.visible&&view.definition.view().surface()==InterfaceDefinition.Surface.SCREEN)try{mount(view,host);}catch(Exception error){block(view.id,error.getMessage());}}
    private static void paintWitness(View view){view.content.rendered().onPaint(()->{if(current(view)&&visible(view)&&!view.blocked)view.paintedTick=tick;});}
    private static boolean visible(View view){return view.visible&&(view.definition.view().surface()==InterfaceDefinition.Surface.HUD?!Minecraft.getInstance().options.hideGui:NativeWorkspaceScreen.visible()&&view.host!=null&&view.host.activeContext()&&view.window!=null&&view.window.visible());}
    public static void admitted(String id){var view=require(id);view.ready=true;view.nextRead.clear();}
    public static boolean owns(String id){return VIEWS.containsKey(id);}
    public static boolean rendered(String id){var v=VIEWS.get(id);return v!=null&&current(v)&&v.ready&&visible(v)&&!v.blocked&&v.paintedTick>=0&&tick-v.paintedTick<=2;}
    public static boolean painted(String id){var v=VIEWS.get(id);return v!=null&&current(v)&&visible(v)&&!v.blocked&&v.paintedTick>=0&&tick-v.paintedTick<=2;}
    public static boolean visible(String id){var v=VIEWS.get(id);return v!=null&&visible(v);}
    public static PackagePreviewTransfer.Resolved asset(String id){return require(id).asset;}
    static Map<String,JsonNode> smokeData(String id){return require(id).content.data();}
    public static boolean passive(String id){return require(id).definition.view().surface()==InterfaceDefinition.Surface.HUD;}
    public static long lifecycle(String id){var v=VIEWS.get(id);return v==null?-1:v.lifecycle;}
    public static String document(String id){return require(id).document;}
    public static Session session(String id){var view=VIEWS.get(id);return view==null||!current(view)||view.blocked||!view.ready||!visible(view)?null:view.session;}
    public static Session rawSession(String id){var view=VIEWS.get(id);return view==null?null:view.session;}
    public static void rebind(String id,Session source,Session target){var view=require(id);if(!Objects.equals(view.session,source))throw new SecurityException("NATIVE_PACKAGE_STALE_SESSION");if(!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(source,target)){view.lifecycle++;view.reading.clear();view.refreshing.clear();}view.session=target;view.ready=true;view.blocked=false;view.content.rendered().root.setActive(true);view.nextRead.clear();}
    public static void block(String id,String error){var view=VIEWS.get(id);if(view!=null){view.blocked=true;view.error=error;view.content.interactive(false);view.content.rendered().root.setActive(false);}}
    public static void show(String id){var view=require(id);view.visible=true;view.content.visible(true);dev.mineagent.runtime.neoforge.client.webui.HudPersistenceClient.visible(id,true);if(view.definition.view().surface()==InterfaceDefinition.Surface.HUD){view.content.interactive(false);LdHudRegistry.attach(view.content,view.definition.view().order());}else if(view.window==null||view.window.closed()||view.content.rendered().root.getParent()==null)mount(view);else view.window.reveal();}
    public static void hide(String id){var view=require(id);view.visible=false;view.content.visible(false);dev.mineagent.runtime.neoforge.client.webui.HudPersistenceClient.visible(id,false);view.content.interactive(false);if(view.window!=null)view.window.dialog.setDisplay(false);}
    public static void close(String id){close(id,true);}
    private static void close(String id,boolean user){var view=VIEWS.remove(id);if(view==null)return;view.closed=true;if(view.projection!=null&&Minecraft.getInstance().screen==view.projection)Minecraft.getInstance().setScreen(null);if(view.presentation!=null)view.presentation.completeExceptionally(new IllegalStateException("VIEW_CLOSED"));PackageContentClient.close(id);if(user)dev.mineagent.runtime.neoforge.client.webui.HudPersistenceClient.closed(id);else dev.mineagent.runtime.neoforge.client.webui.HudPersistenceClient.retired(id);if(view.window!=null)view.window.close();view.content.close();view.resources.close();}
    public static Set<String> ids(){return Set.copyOf(VIEWS.keySet());}
    public static void hostEvent(String channel,com.google.gson.JsonElement payload){
        if(!payload.isJsonObject())return;var data=payload.getAsJsonObject();String id=data.has("viewId")?data.get("viewId").getAsString():"";if(!owns(id))return;
        switch(channel){
            case "closeManagedView"->close(id);
            case "revealPackage"->show(id);
            case "packageViewOutdated"->invalidate(id);
            case "contentError"->{String error=data.has("code")?data.get("code").getAsString():"NATIVE_PACKAGE_ERROR";block(id,error);NativeWorkspaceScreen.notice(error);}
            case "hudRestoreLayout"->{var saved=data.getAsJsonObject("layout");var value=new com.google.gson.JsonObject();value.add("bounds",saved);restoreLayout(id,value);}
            case "contentHotSwap"->{
                String prior=data.get("oldViewId").getAsString();var target=require(id);var old=require(prior);
                if(!target.asset.runtimePackage().packageId().equals(old.asset.runtimePackage().packageId())||target.asset.runtimePackage().revision()!=old.asset.runtimePackage().revision()+1||!rendered(id))throw new IllegalStateException("STALE_TRANSITION");
                var bounds=layout(prior);restoreLayout(id,bounds);awaitPaint(id,System.currentTimeMillis()+5000).whenComplete((unused,error)->{
                    var receipt=new com.google.gson.JsonObject();receipt.add("id",data.get("id"));receipt.addProperty("status",error==null?"SWAPPED":"FAILED");
                    if(error==null)close(prior);try{dev.mineagent.runtime.neoforge.client.webui.ContentHotSwapClient.acknowledge(receipt);}catch(IllegalStateException retired){NativeWorkspaceScreen.notice("UI_TRANSITION_CONTEXT_CHANGED");}
                });
            }
            default->{}
        }
    }
    private static CompletableFuture<Void> awaitPaint(String id,long deadline){
        if(!owns(id)||System.currentTimeMillis()>deadline)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));if(rendered(id))return CompletableFuture.completedFuture(null);
        var result=new CompletableFuture<Void>();CompletableFuture.delayedExecutor(50,TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(()->awaitPaint(id,deadline).whenComplete((v,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(null);})));return result;
    }
    public static void invalidate(String id){block(id,"NATIVE_PACKAGE_OUTDATED");}
    private static View require(String id){var value=VIEWS.get(id);if(value==null||!current(value))throw new IllegalStateException("VIEW_NOT_RENDERED");return value;}
    private static boolean current(View view){var mc=Minecraft.getInstance();return !view.closed&&connection==mc.getConnection()&&level==mc.level&&player==mc.player&&VIEWS.get(view.id)==view;}
    public static Map<String,Object> inspect(String id){
        var view=require(id);var nodes=new ArrayList<Map<String,Object>>();inspect(view,view.definition.view().root(),nodes,true,false,0,0,view.content.rendered().root.getSizeWidth(),view.content.rendered().root.getSizeHeight());
        var root=view.content.rendered().root;
        var text=new StringBuilder();for(var row:nodes)if(Boolean.TRUE.equals(row.get("visible"))&&!Boolean.TRUE.equals(row.get("secret"))&&!Objects.toString(row.get("text"),"").isBlank())text.append(row.get("text")).append('\n');
        var out=new LinkedHashMap<String,Object>();out.put("status",rendered(id)?"OBSERVED":"VIEW_NOT_RENDERED");out.put("documentId",view.document);out.put("renderer","LDLIB2");out.put("elements",nodes.stream().filter(n->Boolean.TRUE.equals(n.get("visible"))).limit(128).toList());out.put("candidateElements",nodes.size());out.put("elementBudget",128);out.put("visibleText",text.substring(0,Math.min(8192,text.length())));out.put("visibleTextTruncated",text.length()>8192);out.put("sensitiveVisible",nodes.stream().anyMatch(n->Boolean.TRUE.equals(n.get("visible"))&&Boolean.TRUE.equals(n.get("secret"))));out.put("viewport",Map.of("width",root.getSizeWidth(),"height",root.getSizeHeight(),"elementRef",root.getId(),"scrollX",0,"scrollY",0));out.put("supportedActions",List.of("click","clickAt","fill","select","toggle","scroll","capture","waitFor","verify","present","done"));out.put("visible",visible(view));out.put("interactive",view.content.interactive());out.put("paintedTick",view.paintedTick);out.put("error",view.error);return out;
    }
    private static boolean secret(JsonNode spec){return spec.path("secret").asBoolean(false);}
    private static void inspect(View view,JsonNode spec,List<Map<String,Object>> rows,boolean ancestorVisible,boolean ancestorDisabled,float clipX,float clipY,float clipR,float clipB){
        var element=view.content.rendered().node(spec.path("id").asText());var root=view.content.rendered().root;if(element==null)return;
        float x=element.getPositionX()-root.getPositionX(),y=element.getPositionY()-root.getPositionY(),right=x+element.getSizeWidth(),bottom=y+element.getSizeHeight();
        boolean displayed=ancestorVisible&&element.isDisplayed()&&element.isVisible();boolean visible=displayed&&right>clipX&&bottom>clipY&&x<clipR&&y<clipB,disabled=ancestorDisabled||!element.isActive();
        var row=new LinkedHashMap<String,Object>();row.put("elementRef",spec.path("id").asText());row.put("dataAiId",spec.path("id").asText());row.put("tag",spec.path("type").asText());row.put("role",spec.path("type").asText());row.put("visible",visible);row.put("disabled",disabled);row.put("bounds",Map.of("x",Math.max(clipX,x),"y",Math.max(clipY,y),"width",Math.max(0,Math.min(clipR,right)-Math.max(clipX,x)),"height",Math.max(0,Math.min(clipB,bottom)-Math.max(clipY,y))));row.put("sensitive",secret(spec));row.put("secret",secret(spec));
        JsonNode value=spec.path("bindings").has("text")?InterfaceExpression.evaluate(spec.path("bindings").get("text"),view.content.data()):view.content.data().getOrDefault(spec.path("bind").asText(),spec.has("value")?spec.get("value"):spec.path("text"));
        String text=secret(spec)?"":value.isValueNode()?value.asText():"";if(spec.path("type").asText().equals("select"))row.put("options",JSON.convertValue(spec.path("options"),List.class));row.put("text",text);row.put("label",secret(spec)?"":spec.path("text").asText(text));row.put("value",secret(spec)?"":text);rows.add(row);
        if(element instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroll){clipX=Math.max(clipX,scroll.viewPort.getPositionX()-root.getPositionX());clipY=Math.max(clipY,scroll.viewPort.getPositionY()-root.getPositionY());clipR=Math.min(clipR,scroll.viewPort.getPositionX()-root.getPositionX()+scroll.viewPort.getSizeWidth());clipB=Math.min(clipB,scroll.viewPort.getPositionY()-root.getPositionY()+scroll.viewPort.getSizeHeight());}
        for(var child:spec.path("children"))inspect(view,child,rows,displayed,disabled,clipX,clipY,clipR,clipB);
    }

    private static void event(View view,String node,String event,String value){
        if(!current(view)||!view.ready||view.blocked||!visible(view))return;
        try{
            view.error="";if(!view.agentDispatch){PackageContentClient.humanInput(view.id);dev.mineagent.runtime.neoforge.client.webui.ContentTakeoverClient.humanInput(view.id);dev.mineagent.runtime.neoforge.client.webui.ContentHotSwapClient.humanInput(view.id);if(view.session!=null&&view.session.binding().actorKind()==ActorKind.AGENT){dev.mineagent.runtime.neoforge.client.webui.UiAgentClient.stop(view.id,true,"HUMAN_INPUT");return;}}
            long revision=view.content.revision();var actions=view.content.actions(view.content.scope(),revision,node,event);
            var spec=view.definition.view().node(node).orElseThrow();if(event.equals("change")&&spec.has("bind"))view.content.input(view.content.scope(),revision,node,spec.path("type").asText().equals("toggle")?BooleanNode.valueOf(Boolean.parseBoolean(value)):TextNode.valueOf(value),updatedValues->update(view,updatedValues));
            var data=view.content.data();var patch=new LinkedHashMap<String,JsonNode>();var emitted=new ArrayList<Map.Entry<String,Map<String,JsonNode>>>();
            for(var action:actions){String op=action.path("op").asText(),key=action.path("key").asText();if(op.equals("set")){var next=action.has("expr")?InterfaceExpression.evaluate(action.get("expr"),data):action.has("from")?spec.path("type").asText().equals("toggle")?BooleanNode.valueOf(Boolean.parseBoolean(value)):TextNode.valueOf(value):action.get("value");data.put(key,next);patch.put(key,next);}else if(op.equals("toggle")){var next=BooleanNode.valueOf(!data.getOrDefault(key,BooleanNode.FALSE).asBoolean());data.put(key,next);patch.put(key,next);}else if(op.equals("emit")){var context=new LinkedHashMap<>(data);context.put("event",action.path("args").deepCopy());context.put("eventValue",TextNode.valueOf(value));emitted.add(Map.entry(action.path("action").asText(),Map.copyOf(context)));}}
            if(!patch.isEmpty()){var applied=view.content.localData(view.content.scope(),revision,patch,updatedValues->update(view,updatedValues));if(!applied.applied())throw new IllegalArgumentException(applied.error());}
            for(var action:emitted){var request=view.definition.actions().get(action.getKey());if(request==null)throw new IllegalArgumentException("NATIVE_PACKAGE_ACTION_UNDECLARED");dispatch(view,action.getKey(),request,action.getValue(),true);}
        }catch(Exception failure){view.error=Objects.toString(failure.getMessage(),"NATIVE_PACKAGE_EVENT_FAILED");}
    }
    private static void dispatch(View view,String key,NativePackageDefinition.Request request,Map<String,JsonNode> data,boolean write){
        if(!current(view)||!view.ready||view.blocked||view.session==null&&!request.action().equals("state.get")||view.transport==null){view.error="PREVIEW_READ_ONLY";return;}
        var arguments=!write&&view.refreshing.remove(key)?Map.of("refresh","true"):request.arguments(data);
        if(write&&view.writes.containsKey(key)){view.error="NATIVE_PACKAGE_OUTCOME_PENDING";return;}if(!write&&!view.reading.add(key))return;UUID operation=UUID.randomUUID();if(write)view.writes.put(key,operation);
        Session source=view.session;long generation=view.lifecycle;
        view.transport.request(view.id,source,request.action(),arguments,operation).whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{
            if(!current(view)||view.lifecycle!=generation||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(view.session,source))return;
            if(!write)view.reading.remove(key);if(error!=null){view.error="NATIVE_PACKAGE_OUTCOME_UNKNOWN";return;}
            if(write&&!Set.of(Code.TIMEOUT,Code.IN_PROGRESS).contains(receipt.code()))view.writes.remove(key);
            try{
                var value=JSON.createObjectNode().put("code",receipt.code().name());var values=value.putObject("values");
                for(var entry:receipt.values().entrySet()){JsonNode decoded;try{decoded=JSON.readTree(entry.getValue());if(decoded==null)decoded=TextNode.valueOf(entry.getValue());}catch(Exception ignored){decoded=TextNode.valueOf(entry.getValue());}values.set(entry.getKey(),decoded);}
                value.set("data",values.has("state")?values.get("state").deepCopy():values.deepCopy());
                var applied=view.content.patch(view.content.scope(),view.content.revision(),view.content.dataRevision(),Map.of(request.result(),value),updatedValues->update(view,updatedValues));
                if(!applied.applied())throw new IllegalStateException(applied.error());view.error="";
                if(request.action().equals("delivery.read")&&receipt.code()==Code.OBSERVED&&receipt.values().containsKey("nativeDeliveryReadToken")){
                    String token=receipt.values().get("nativeDeliveryReadToken");dev.mineagent.runtime.neoforge.client.webui.ContentDeliveryClient.acknowledgeData(view.id,source,Map.of("token",token),UUID.fromString(token)).exceptionally(failure->null);
                }
            }catch(Exception failure){view.error=Objects.toString(failure.getMessage(),"NATIVE_PACKAGE_RECEIPT_INVALID");}
        }));
    }
    public static com.google.gson.JsonObject layout(String id){
        var view=require(id);var mc=Minecraft.getInstance();var root=view.window==null?view.content.rendered().root:view.window.dialog.overlay;
        if(root.getSizeWidth()<=0||root.getSizeHeight()<=0)throw new IllegalStateException("LAYOUT_PENDING");
        var bounds=Map.of("x",root.getPositionX(),"y",root.getPositionY(),"width",root.getSizeWidth(),"height",root.getSizeHeight());String signature=bounds+"|"+view.opacity;
        if(!signature.equals(view.layoutSignature)){if(!view.layoutSignature.isEmpty()){view.layoutRevision++;view.layoutSaveAt=tick+12;if(view.presentation==null)view.layoutSource="PLAYER";}view.layoutSignature=signature;}
        var result=new com.google.gson.Gson().toJsonTree(Map.of("viewId",id,"hostDocumentId",view.hostDocument,"revision",view.layoutRevision,"source",view.layoutSource,"visible",visible(view),"minimized",!visible(view),"bounds",bounds,"area",Map.of("x",7,"y",48,"width",Math.max(240,mc.getWindow().getGuiScaledWidth()-14),"height",Math.max(160,mc.getWindow().getGuiScaledHeight()-130)),"opacity",view.opacity)).getAsJsonObject();
        if(rendered(id))result.add("opacityPaint",new com.google.gson.Gson().toJsonTree(Map.of("status","NATIVE_LDLIB2_PAINTED","hostDocumentId",view.hostDocument,"viewId",id,"layoutRevision",view.layoutRevision,"paintSequence",view.paintedTick+1,"opacity",view.opacity,"alpha",Math.round(view.opacity*255))));return result;
    }
    public static void restoreLayout(String id,com.google.gson.JsonObject saved){
        var view=require(id);if(view.layoutRevision>1||!saved.has("bounds"))return;
        var root=view.window==null?view.content.rendered().root:view.window.dialog.overlay;
        if(root.getSizeWidth()<=0||root.getSizeHeight()<=0){var deferred=saved.deepCopy();root.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,event->{event.currentElement.removeEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,event.currentListener);if(current(view))restoreLayout(id,deferred);});return;}
        var b=saved.getAsJsonObject("bounds");applyBounds(view,b.get("x").getAsFloat(),b.get("y").getAsFloat(),b.get("width").getAsFloat(),b.get("height").getAsFloat(),saved.has("opacity")?saved.get("opacity").getAsDouble():1);if(saved.has("minimized")&&saved.get("minimized").getAsBoolean())hide(id);
    }
    private static void applyBounds(View view,float x,float y,float w,float h,double opacity){
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(w)||!Float.isFinite(h)||!Double.isFinite(opacity)||opacity<0||opacity>1)throw new IllegalArgumentException("UI_LAYOUT_INVALID");
        var mc=Minecraft.getInstance();w=Math.clamp(w,64,Math.max(64,mc.getWindow().getGuiScaledWidth()-14));h=Math.clamp(h,64,Math.max(64,mc.getWindow().getGuiScaledHeight()-82));x=Math.clamp(x,0,Math.max(0,mc.getWindow().getGuiScaledWidth()-w));y=Math.clamp(y,0,Math.max(0,mc.getWindow().getGuiScaledHeight()-h));
        if(view.window!=null){view.window.restore(new WorkspaceWindow.Placement(x,y,w,h,false));view.window.dialog.overlay.getStyle().opacity((float)opacity);}else{view.content.rendered().root.getLayout().left(x).top(y).width(w).height(h);view.content.rendered().root.getStyle().opacity((float)opacity);}
        view.opacity=opacity;view.paintedTick=-1;
    }
    public static CompletableFuture<com.google.gson.JsonObject> present(String id,dev.mineagent.runtime.core.ui.UiPresentationAction action){
        var view=require(id);var layout=layout(id);if(layout.get("revision").getAsLong()!=action.expectedLayoutRevision()||view.presentation!=null)throw new IllegalStateException("STALE_LAYOUT");
        var a=layout.getAsJsonObject("area");var bounds=action.placement().resolve(new dev.mineagent.runtime.core.ui.UiPresentationAction.Bounds(a.get("x").getAsDouble(),a.get("y").getAsDouble(),a.get("width").getAsDouble(),a.get("height").getAsDouble()));
        applyBounds(view,(float)bounds.x(),(float)bounds.y(),(float)bounds.width(),(float)bounds.height(),action.placement().opacity()==null?view.opacity:action.placement().opacity());
        view.layoutSource="AGENT";view.presentation=new CompletableFuture<>();view.presentAction=action;view.presentAfter=tick+3;view.presentDeadline=tick+100;return view.presentation;
    }
    private static com.google.gson.JsonObject stableLayout(com.google.gson.JsonObject value){var copy=value.deepCopy();copy.remove("opacityPaint");return copy;}
    private static void placementTick(View view){
        try{
            if(!visible(view))return;var actual=layout(view.id);
            if(view.presentation!=null){
                if(tick>view.presentDeadline){view.presentation.completeExceptionally(new IllegalStateException("UI_PRESENTATION_UNKNOWN"));view.presentation=null;return;}
                if(tick<view.presentAfter||!rendered(view.id)||view.savingPresentation)return;
                view.savingPresentation=true;var pending=view.presentation;var action=view.presentAction;
                NativePackagePlacement.save(view.id,actual).whenComplete((ignored,error)->{
                    view.savingPresentation=false;if(view.presentation!=pending)return;view.presentation=null;
                    if(error!=null||!current(view)||!rendered(view.id)||!stableLayout(actual).equals(stableLayout(layout(view.id)))){pending.completeExceptionally(error==null?new IllegalStateException("UI_PRESENTATION_UNKNOWN"):error);return;}
                    view.layoutSaveAt=0;var receipt=new com.google.gson.JsonObject();receipt.addProperty("operationId",action.operationId().toString());receipt.addProperty("documentId",view.document);receipt.addProperty("status","APPLIED_HOST");receipt.addProperty("persisted",true);receipt.addProperty("nativePainted",true);receipt.addProperty("executionMode","HOST_PRESENTATION");receipt.addProperty("afterLayoutRevision",actual.get("revision").getAsLong());receipt.add("opacityPaint",layout(view.id).get("opacityPaint"));receipt.add("actual",actual);pending.complete(receipt);
                });
            }else if(view.layoutSaveAt>0&&tick>=view.layoutSaveAt){view.layoutSaveAt=0;NativePackagePlacement.save(view.id,actual).exceptionally(error->{NativeWorkspaceScreen.notice("UI_LAYOUT_SAVE_FAILED");return null;});}
        }catch(Exception error){if(view.presentation!=null){view.presentation.completeExceptionally(error);view.presentation=null;}}
    }

    public static void interact(String id)throws Exception{
        var view=require(id);if(!view.ready||view.blocked||!passive(id))throw new IllegalStateException("HUD_NOT_READY");
        var rendered=KubeInterfaceRenderer.build(view.definition.view(),view.content.data(),(node,event,value)->event(view,node,event,value),view.resources.textures(),false);
        if(view.projection!=null&&Minecraft.getInstance().screen==view.projection)Minecraft.getInstance().setScreen(null);
        var screen=new HudScreen(view,rendered);view.projection=screen;view.content.interactive(true);rendered.onPaint(()->{if(current(view)&&Minecraft.getInstance().screen==screen)view.paintedTick=tick;});Minecraft.getInstance().setScreen(screen);
    }
    private static void update(View view,Map<String,JsonNode> data){view.content.rendered().update(data);if(view.projection!=null&&Minecraft.getInstance().screen==view.projection)view.projection.rendered.update(data);}
    private static final class HudScreen extends NativeInputScreen {
        final View view;final LdInterfaceRenderer.Rendered rendered;
        HudScreen(View view,LdInterfaceRenderer.Rendered rendered){super(rendered.ui,net.minecraft.network.chat.Component.literal(view.definition.view().title()));this.view=view;this.rendered=rendered;}
        @Override public void removed(){if(view.projection==this){view.projection=null;if(!view.closed){view.content.interactive(false);if(view.content.visible())LdHudRegistry.attach(view.content,view.definition.view().order());}}super.removed();rendered.close();}
    }
    public static void refresh(String id,String action){var view=VIEWS.get(id);if(view==null)return;for(var read:view.definition.reads().entrySet())if(read.getValue().action().equals(action))view.nextRead.put(read.getKey(),0L);}
    public static void refreshWorld(String id){var view=VIEWS.get(id);if(view==null)return;for(var read:view.definition.reads().entrySet())if(read.getValue().action().equals("worldui.read")){view.refreshing.add(read.getKey());view.nextRead.put(read.getKey(),0L);}}
    public static String identity(String id){var view=require(id);return dev.mineagent.runtime.api.ui.UiCapture.sha256((view.document+"|"+view.content.revision()+"|"+view.content.dataRevision()+"|"+inspect(id).get("viewport")+"|"+inspect(id).get("elements")).getBytes(StandardCharsets.UTF_8));}
    public static CompletableFuture<NativeViewCapture.Captured> capture(String id){
        try{var view=require(id);if(!rendered(id))throw new IllegalStateException("VIEW_NOT_RENDERED");if(view.content.rendered().popupOpen())throw new IllegalStateException("VIEW_POPUP_ACTIVE");var sensitive=new boolean[1];InterfaceDefinition.walk(view.definition.view().root(),n->{if(secret(n)&&view.definition.view().interactiveNode(n.path("id").asText(),view.content.data()))sensitive[0]=true;});if(sensitive[0])throw new SecurityException("SENSITIVE_VIEW");
            String expected=identity(id);var root=view.content.rendered().root;int width=(int)Math.ceil(root.getSizeWidth()),height=(int)Math.ceil(root.getSizeHeight());
            var original=view.definition.view();var definition=new InterfaceDefinition(original.id(),original.title(),InterfaceDefinition.Surface.SCREEN,original.root(),original.data(),original.stylesheet(),original.order(),Map.of(),Map.of());
            var isolated=KubeInterfaceRenderer.build(definition,view.content.data(),(a,b,c)->{},view.resources.textures(),false);
            return NativeViewCapture.capture(isolated,view.document,expected,width,height,()->current(view)&&rendered(id)&&expected.equals(identity(id)),view.content.rendered());
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    public static Map<String,Object> draft(String id,boolean seal){
        var view=require(id);if(view.sealedDraft!=null&&seal)return view.sealedDraft;var controls=new ArrayList<Map<String,Object>>();var data=view.content.data();
        InterfaceDefinition.walk(view.definition.view().root(),spec->{String type=spec.path("type").asText();if(!Set.of("input","toggle","select").contains(type)||secret(spec)||!spec.has("bind"))return;String node=spec.path("id").asText();if(!view.definition.view().interactiveNode(node,data))return;
            var value=data.getOrDefault(spec.path("bind").asText(),spec.path("value"));String text=value.isMissingNode()?"":value.asText();if(text.length()>8192||controls.size()>=64)throw new IllegalArgumentException("DRAFT_BUDGET");
            controls.add(Map.of("attribute","id","locator",node,"tag",type.equals("select")?"select":"input","type",type.equals("select")?"select-one":type.equals("toggle")?"checkbox":"text","name",spec.path("bind").asText(),"value",text,"checked",value.asBoolean(false),"selected",type.equals("select")?List.of(text):List.of()));
        });
        var result=Map.<String,Object>of("status","DRAFT_CAPTURED","version",1,"controls",controls);if(result.toString().length()>49152)throw new IllegalArgumentException("DRAFT_BUDGET");
        if(seal){view.sealedDraft=result;view.content.interactive(false);view.content.rendered().root.setActive(false);}return result;
    }
    public static Map<String,Object> restoreDraft(String id,JsonNode draft,JsonNode expected){
        var view=require(id);if(!view.ready||view.blocked||view.sealedDraft!=null||!visible(view))throw new IllegalStateException("VIEW_NOT_RENDERED");
        if(expected!=null&&!JSON.valueToTree(draft(id,false)).path("controls").equals(expected.path("controls")))throw new IllegalStateException("DRAFT_CURRENT_CHANGED");
        if(draft.path("version").asInt()!=1||!draft.path("controls").isArray()||draft.path("controls").size()>64||draft.toString().length()>49152)throw new IllegalArgumentException("DRAFT_BUDGET");
        var values=new LinkedHashMap<String,JsonNode>();var nodes=new HashSet<String>();
        for(var control:draft.path("controls")){String node=control.path("locator").asText();if(!nodes.add(node))throw new IllegalArgumentException("DRAFT_AMBIGUOUS_TARGET");var spec=view.definition.view().node(node).orElseThrow(()->new IllegalArgumentException("DRAFT_TARGET_CHANGED"));
            String type=spec.path("type").asText();if(!control.path("attribute").asText().equals("id")||!control.path("tag").asText().equals(type.equals("select")?"select":"input")||!Set.of("input","toggle","select").contains(type)||!control.path("type").asText().equals(type.equals("select")?"select-one":type.equals("toggle")?"checkbox":"text")||!control.path("name").asText().equals(spec.path("bind").asText())||!spec.has("bind")||secret(spec)||!view.definition.view().interactiveNode(node,view.content.data()))throw new IllegalArgumentException("DRAFT_TARGET_CHANGED");
            var value=type.equals("toggle")?control.path("checked"):control.path("value");if(type.equals("toggle")?!value.isBoolean():!value.isTextual()||value.asText().length()>8192)throw new IllegalArgumentException("DRAFT_BUDGET");values.put(node,value);
        }
        var receipt=view.content.restoreInputs(view.content.scope(),view.content.revision(),values,updatedValues->update(view,updatedValues));if(!receipt.applied())throw new IllegalStateException(receipt.error());
        return Map.of("status","DRAFT_RESTORED","count",values.size(),"eventsDispatched",false);
    }
    public static Map<String,Object> act(String id,JsonNode action,String expected){
        var view=require(id);if(!rendered(id)||!view.content.interactive()||view.blocked||view.sealedDraft!=null)throw new IllegalStateException("NOT_INTERACTABLE");if(!identity(id).equals(expected))throw new IllegalStateException("STALE_VIEW");
        String name=action.path("action").asText(),node=action.path("elementRef").asText();
        if(name.equals("scroll")&&(node.isBlank()||node.equals("viewport"))){var scrollers=new ArrayList<String>();InterfaceDefinition.walk(view.definition.view().root(),n->{if(n.path("type").asText().equals("scroll")&&view.definition.view().interactiveNode(n.path("id").asText(),view.content.data()))scrollers.add(n.path("id").asText());});if(scrollers.isEmpty())throw new IllegalArgumentException("NOT_INTERACTABLE");node=scrollers.getFirst();}
        var spec=view.definition.view().node(node).orElseThrow(()->new IllegalArgumentException("TARGET_NOT_FOUND"));
        if(secret(spec)||!view.definition.view().interactiveNode(node,view.content.data()))throw new IllegalStateException("NOT_INTERACTABLE");
        String value="";switch(name){case "click"->{if(!spec.path("events").has("click"))throw new IllegalArgumentException("NOT_INTERACTABLE");}case "fill"->{if(!spec.path("type").asText().equals("input")||!action.path("value").isTextual()||action.path("value").asText().length()>8192)throw new IllegalArgumentException("NOT_INTERACTABLE");value=action.path("value").asText();}case "select"->{if(!spec.path("type").asText().equals("select")||!action.path("value").isTextual())throw new IllegalArgumentException("NOT_INTERACTABLE");value=action.path("value").asText();}case "toggle"->{if(!spec.path("type").asText().equals("toggle")||!action.path("checked").isBoolean())throw new IllegalArgumentException("NOT_INTERACTABLE");value=action.path("checked").asText();}case "scroll"->{var element=view.content.rendered().node(node);if(!(element instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroller))throw new IllegalArgumentException("NOT_INTERACTABLE");double dx=action.path("x").asDouble(0),dy=action.path("y").asDouble(action.path("deltaY").asDouble(0));if(!Double.isFinite(dx)||!Double.isFinite(dy)||Math.abs(dx)>10000||Math.abs(dy)>10000)throw new IllegalArgumentException("SCROLL_RANGE");boolean absolute=action.path("mode").asText("by").equals("to");float rx=Math.max(0,scroller.getContainerWidth()-scroller.viewPort.getContentWidth()),ry=Math.max(0,scroller.getContainerHeight()-scroller.viewPort.getContentHeight());if(rx>0)scroller.horizontalScroller.setNormalizedValue((float)Math.clamp((dx+(absolute?0:scroller.horizontalScroller.getNormalizedValue()*rx))/rx,0,1));if(ry>0)scroller.verticalScroller.setNormalizedValue((float)Math.clamp((dy+(absolute?0:scroller.verticalScroller.getNormalizedValue()*ry))/ry,0,1));return Map.of("status","APPLIED_NATIVE","businessVerified",false,"executionMode","LDLIB2");}default->throw new IllegalArgumentException("UNSUPPORTED");}
        view.agentDispatch=true;try{event(view,node,name.equals("click")?"click":"change",value);}finally{view.agentDispatch=false;}
        if(!view.error.isBlank())throw new IllegalStateException(view.error);return Map.of("status","APPLIED_NATIVE","businessVerified",false,"executionMode","LDLIB2");
    }

    public static void tick(){thread();context();tick++;for(var view:List.copyOf(VIEWS.values())){if(view.window!=null&&view.window.closed()){close(view.id);continue;}boolean shown=visible(view);if(shown!=view.observedVisibility){view.observedVisibility=shown;PackageContentClient.visibility(view.id,shown);}if(!current(view)||!view.ready||!shown||view.blocked)continue;placementTick(view);for(var entry:view.definition.reads().entrySet()){var request=entry.getValue();if(tick<view.nextRead.getOrDefault(entry.getKey(),0L))continue;view.nextRead.put(entry.getKey(),request.intervalTicks()==0?Long.MAX_VALUE:tick+request.intervalTicks());dispatch(view,entry.getKey(),request,view.content.data(),false);}}}
    private static void context(){var mc=Minecraft.getInstance();if(connection!=mc.getConnection()||level!=mc.level||player!=mc.player){for(String id:List.copyOf(VIEWS.keySet()))close(id,false);connection=mc.getConnection();level=mc.level;player=mc.player;}}
    public static void clear(){thread();for(String id:List.copyOf(VIEWS.keySet()))close(id,false);}
    private static void thread(){if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("NATIVE_PACKAGE_CLIENT_THREAD");}
    private NativePackageViews(){}
}
