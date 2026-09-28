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
    private static Object connection,level;private static long tick;
    private static final class View {
        final String id,document=UUID.randomUUID().toString();final PackagePreviewTransfer.Resolved asset;final InterfaceSession<LdInterfaceRenderer.Rendered> content;
        NativePackageDefinition definition;NativePackageResources resources;Session session;Transport transport;WorkspaceWindow window;NativeWorkspaceScreen host;boolean ready,blocked,closed,visible=true,observedVisibility;long paintedTick=-1,lifecycle=1;
        boolean agentDispatch;Map<String,Object> sealedDraft;final Set<String> refreshing=new HashSet<>();final Map<String,Long> nextRead=new HashMap<>();final Set<String> reading=new HashSet<>();final Map<String,UUID> writes=new HashMap<>();String error="";
        View(String id,PackagePreviewTransfer.Resolved asset,Session session,NativePackageDefinition definition,Transport transport){
            this.id=id;this.asset=asset;this.session=session;this.definition=definition;this.transport=transport;
            var mc=Minecraft.getInstance();var world=session==null?NativeWorkspaceConnection.current().binding().worldId():session.binding().worldId();
            content=new InterfaceSession<>(new InterfaceSession.Scope(world,mc.player.getUUID(),asset.runtimePackage().packageId(),UUID.randomUUID(),definition.view().id()));
        }
    }
    public static String open(PackagePreviewTransfer.Resolved asset,Session session,boolean passive,Transport transport)throws Exception {
        thread();context();var mc=Minecraft.getInstance();if(mc.player==null||!NativeWorkspaceConnection.ready())throw new IllegalStateException("VIEW_NOT_RENDERED");
        if(session!=null&&(!session.binding().viewerPlayerId().equals(mc.player.getUUID())||!session.binding().ownerPackageId().equals(asset.runtimePackage().packageId())||session.binding().packageRevision()!=asset.runtimePackage().revision()||!session.binding().entryPath().equals(asset.entry())))throw new SecurityException("NATIVE_PACKAGE_BINDING");
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
            if(session==null)view.ready=true;
            return id;
        }catch(Exception failure){VIEWS.remove(id,view);view.content.close();view.resources.close();throw failure;}
    }
    private static void resources(JsonNode node,Set<String> values){if(node.has("resource"))values.add(node.get("resource").asText());for(var child:node.path("children"))resources(child,values);}
    private static void mount(View view){mount(view,NativeWorkspaceScreen.previewHost());}
    private static void mount(View view,NativeWorkspaceScreen host){
        if(view.host!=null&&view.host!=host){var receipt=view.content.remount((d,data)->KubeInterfaceRenderer.build(d,data,(node,event,value)->event(view,node,event,value),view.resources.textures(),true));if(!receipt.applied())throw new IllegalStateException(receipt.error());paintWitness(view);}
        view.host=host;view.window=host.window("native-package-"+view.id,view.definition.view().title(),480,340);view.window.body.clearAllChildren();view.content.rendered().root.getLayout().widthPercent(100).heightPercent(100);view.window.body.addChild(view.content.rendered().root);view.content.interactive(!view.blocked);view.paintedTick=-1;
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
    public static void show(String id){var view=require(id);view.visible=true;view.content.visible(true);if(view.definition.view().surface()==InterfaceDefinition.Surface.HUD){view.content.interactive(false);LdHudRegistry.attach(view.content,view.definition.view().order());}else if(view.window==null||view.window.closed()||view.content.rendered().root.getParent()==null)mount(view);else view.window.reveal();}
    public static void hide(String id){var view=require(id);view.visible=false;view.content.visible(false);view.content.interactive(false);if(view.window!=null)view.window.dialog.setDisplay(false);}
    public static void close(String id){var view=VIEWS.remove(id);if(view==null)return;view.closed=true;PackageContentClient.close(id);if(view.window!=null)view.window.close();view.content.close();view.resources.close();}
    public static Set<String> ids(){return Set.copyOf(VIEWS.keySet());}
    public static void invalidate(String id){block(id,"NATIVE_PACKAGE_OUTDATED");}
    private static View require(String id){var value=VIEWS.get(id);if(value==null||!current(value))throw new IllegalStateException("VIEW_NOT_RENDERED");return value;}
    private static boolean current(View view){var mc=Minecraft.getInstance();return !view.closed&&connection==mc.getConnection()&&level==mc.level&&VIEWS.get(view.id)==view;}
    public static Map<String,Object> inspect(String id){
        var view=require(id);var nodes=new ArrayList<Map<String,Object>>();inspect(view,view.definition.view().root(),nodes);
        var root=view.content.rendered().root;
        return Map.of("status",rendered(id)?"OBSERVED":"VIEW_NOT_RENDERED","documentId",view.document,"renderer","LDLIB2","elements",nodes,"viewport",Map.of("width",root.getSizeWidth(),"height",root.getSizeHeight(),"elementRef",root.getId(),"scrollX",0,"scrollY",0),"visible",visible(view),"interactive",view.content.interactive(),"paintedTick",view.paintedTick,"error",view.error);
    }
    private static boolean secret(JsonNode spec){return spec.path("secret").asBoolean(false)||(spec.path("id").asText()+" "+spec.path("bind").asText()).toLowerCase(Locale.ROOT).matches(".*(password|secret|token|api.?key|cc.number|cc.csc|one.time.code).*");}
    private static void inspect(View view,JsonNode spec,List<Map<String,Object>> rows){
        var element=view.content.rendered().node(spec.path("id").asText());var root=view.content.rendered().root;
        if(element!=null){var row=new LinkedHashMap<String,Object>();row.put("elementRef",spec.path("id").asText());row.put("tag",spec.path("type").asText());row.put("role",spec.path("type").asText());row.put("visible",element.isDisplayed());row.put("disabled",!element.isActive());row.put("bounds",Map.of("x",element.getPositionX()-root.getPositionX(),"y",element.getPositionY()-root.getPositionY(),"width",element.getSizeWidth(),"height",element.getSizeHeight()));row.put("sensitive",secret(spec));
            JsonNode value=spec.path("bindings").has("text")?InterfaceExpression.evaluate(spec.path("bindings").get("text"),view.content.data()):view.content.data().getOrDefault(spec.path("bind").asText(),spec.path("text"));
            row.put("text",secret(spec)?"":value.isValueNode()?value.asText():"");rows.add(row);}
        for(var child:spec.path("children"))inspect(view,child,rows);
    }
    private static void event(View view,String node,String event,String value){
        if(!current(view)||!view.ready||view.blocked||!visible(view))return;
        try{
            view.error="";if(!view.agentDispatch){PackageContentClient.humanInput(view.id);if(view.session!=null&&view.session.binding().actorKind()==ActorKind.AGENT){dev.mineagent.runtime.neoforge.client.webui.UiAgentClient.stop(view.id,true,"HUMAN_INPUT");return;}}
            long revision=view.content.revision();var actions=view.content.actions(view.content.scope(),revision,node,event);
            var spec=view.definition.view().node(node).orElseThrow();if(event.equals("change")&&spec.has("bind"))view.content.input(view.content.scope(),revision,node,spec.path("type").asText().equals("toggle")?BooleanNode.valueOf(Boolean.parseBoolean(value)):TextNode.valueOf(value),view.content.rendered()::update);
            var data=view.content.data();var patch=new LinkedHashMap<String,JsonNode>();var emitted=new ArrayList<Map.Entry<String,Map<String,JsonNode>>>();
            for(var action:actions){String op=action.path("op").asText(),key=action.path("key").asText();if(op.equals("set")){var next=action.has("expr")?InterfaceExpression.evaluate(action.get("expr"),data):action.has("from")?spec.path("type").asText().equals("toggle")?BooleanNode.valueOf(Boolean.parseBoolean(value)):TextNode.valueOf(value):action.get("value");data.put(key,next);patch.put(key,next);}else if(op.equals("toggle")){var next=BooleanNode.valueOf(!data.getOrDefault(key,BooleanNode.FALSE).asBoolean());data.put(key,next);patch.put(key,next);}else if(op.equals("emit")){var context=new LinkedHashMap<>(data);context.put("event",action.path("args").deepCopy());context.put("eventValue",TextNode.valueOf(value));emitted.add(Map.entry(action.path("action").asText(),Map.copyOf(context)));}}
            if(!patch.isEmpty()){var applied=view.content.localData(view.content.scope(),revision,patch,view.content.rendered()::update);if(!applied.applied())throw new IllegalArgumentException(applied.error());}
            for(var action:emitted){var request=view.definition.actions().get(action.getKey());if(request==null)throw new IllegalArgumentException("NATIVE_PACKAGE_ACTION_UNDECLARED");dispatch(view,action.getKey(),request,action.getValue(),true);}
        }catch(Exception failure){view.error=Objects.toString(failure.getMessage(),"NATIVE_PACKAGE_EVENT_FAILED");}
    }
    private static void dispatch(View view,String key,NativePackageDefinition.Request request,Map<String,JsonNode> data,boolean write){
        if(!current(view)||!view.ready||view.blocked||view.session==null&&!request.action().equals("state.get")||view.transport==null){view.error="PREVIEW_READ_ONLY";return;}
        if(write&&view.writes.containsKey(key)){view.error="NATIVE_PACKAGE_OUTCOME_PENDING";return;}if(!write&&!view.reading.add(key))return;UUID operation=UUID.randomUUID();if(write)view.writes.put(key,operation);
        Session source=view.session;long generation=view.lifecycle;
        view.transport.request(view.id,source,request.action(),!write&&view.refreshing.remove(key)?Map.of("refresh","true"):request.arguments(data),operation).whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{
            if(!current(view)||view.lifecycle!=generation||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(view.session,source))return;
            if(!write)view.reading.remove(key);if(error!=null){view.error="NATIVE_PACKAGE_OUTCOME_UNKNOWN";return;}
            if(write&&!Set.of(Code.TIMEOUT,Code.IN_PROGRESS).contains(receipt.code()))view.writes.remove(key);
            try{
                var value=JSON.createObjectNode().put("code",receipt.code().name());var values=value.putObject("values");
                for(var entry:receipt.values().entrySet()){JsonNode decoded;try{decoded=JSON.readTree(entry.getValue());if(decoded==null)decoded=TextNode.valueOf(entry.getValue());}catch(Exception ignored){decoded=TextNode.valueOf(entry.getValue());}values.set(entry.getKey(),decoded);}
                value.set("data",values.has("state")?values.get("state").deepCopy():values.deepCopy());
                var applied=view.content.patch(view.content.scope(),view.content.revision(),view.content.dataRevision(),Map.of(request.result(),value),view.content.rendered()::update);
                if(!applied.applied())throw new IllegalStateException(applied.error());view.error="";
                if(request.action().equals("delivery.read")&&receipt.code()==Code.OBSERVED&&receipt.values().containsKey("nativeDeliveryReadToken")){
                    String token=receipt.values().get("nativeDeliveryReadToken");dev.mineagent.runtime.neoforge.client.webui.ContentDeliveryClient.acknowledgeData(view.id,source,Map.of("token",token),UUID.fromString(token)).exceptionally(failure->null);
                }
            }catch(Exception failure){view.error=Objects.toString(failure.getMessage(),"NATIVE_PACKAGE_RECEIPT_INVALID");}
        }));
    }
    public static void refresh(String id,String action){var view=VIEWS.get(id);if(view==null)return;for(var read:view.definition.reads().entrySet())if(read.getValue().action().equals(action))view.nextRead.put(read.getKey(),0L);}
    public static void refreshWorld(String id){var view=VIEWS.get(id);if(view==null)return;for(var read:view.definition.reads().entrySet())if(read.getValue().action().equals("worldui.read")){view.refreshing.add(read.getKey());view.nextRead.put(read.getKey(),0L);}}
    public static String identity(String id){var view=require(id);return dev.mineagent.runtime.api.ui.UiCapture.sha256((view.document+"|"+view.content.revision()+"|"+view.content.dataRevision()+"|"+inspect(id).get("viewport")+"|"+inspect(id).get("elements")).getBytes(StandardCharsets.UTF_8));}
    public static CompletableFuture<NativeViewCapture.Captured> capture(String id){
        try{var view=require(id);if(!rendered(id))throw new IllegalStateException("VIEW_NOT_RENDERED");var sensitive=new boolean[1];InterfaceDefinition.walk(view.definition.view().root(),n->{if(secret(n)&&view.definition.view().interactiveNode(n.path("id").asText(),view.content.data()))sensitive[0]=true;});if(sensitive[0])throw new SecurityException("SENSITIVE_VIEW");
            String expected=identity(id);var root=view.content.rendered().root;int width=(int)Math.ceil(root.getSizeWidth()),height=(int)Math.ceil(root.getSizeHeight());
            var original=view.definition.view();var definition=new InterfaceDefinition(original.id(),original.title(),InterfaceDefinition.Surface.SCREEN,original.root(),original.data(),original.stylesheet(),original.order(),Map.of(),Map.of());
            var isolated=KubeInterfaceRenderer.build(definition,view.content.data(),(a,b,c)->{},view.resources.textures(),false);
            return NativeViewCapture.capture(isolated,view.document,expected,width,height,()->current(view)&&rendered(id)&&expected.equals(identity(id)));
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    public static Map<String,Object> draft(String id,boolean seal){
        var view=require(id);if(view.sealedDraft!=null&&seal)return view.sealedDraft;var controls=new ArrayList<Map<String,Object>>();var data=view.content.data();
        InterfaceDefinition.walk(view.definition.view().root(),spec->{String type=spec.path("type").asText();if(!Set.of("input","toggle").contains(type)||secret(spec)||!spec.has("bind"))return;String node=spec.path("id").asText();if(!view.definition.view().interactiveNode(node,data))return;
            var value=data.getOrDefault(spec.path("bind").asText(),spec.path("value"));String text=value.isMissingNode()?"":value.asText();if(text.length()>8192||controls.size()>=64)throw new IllegalArgumentException("DRAFT_BUDGET");
            controls.add(Map.of("attribute","id","locator",node,"tag","input","type",type.equals("toggle")?"checkbox":"text","name",spec.path("bind").asText(),"value",text,"checked",value.asBoolean(false),"selected",List.of()));
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
            String type=spec.path("type").asText();if(!control.path("attribute").asText().equals("id")||!control.path("tag").asText().equals("input")||!Set.of("input","toggle").contains(type)||!control.path("type").asText().equals(type.equals("toggle")?"checkbox":"text")||!control.path("name").asText().equals(spec.path("bind").asText())||!spec.has("bind")||secret(spec)||!view.definition.view().interactiveNode(node,view.content.data()))throw new IllegalArgumentException("DRAFT_TARGET_CHANGED");
            var value=type.equals("toggle")?control.path("checked"):control.path("value");if(type.equals("toggle")?!value.isBoolean():!value.isTextual()||value.asText().length()>8192)throw new IllegalArgumentException("DRAFT_BUDGET");values.put(node,value);
        }
        var receipt=view.content.restoreInputs(view.content.scope(),view.content.revision(),values,view.content.rendered()::update);if(!receipt.applied())throw new IllegalStateException(receipt.error());
        return Map.of("status","DRAFT_RESTORED","count",values.size(),"eventsDispatched",false);
    }
    public static Map<String,Object> act(String id,JsonNode action,String expected){
        var view=require(id);if(!rendered(id)||!view.content.interactive()||view.blocked||view.sealedDraft!=null)throw new IllegalStateException("NOT_INTERACTABLE");if(!identity(id).equals(expected))throw new IllegalStateException("STALE_VIEW");
        String name=action.path("action").asText(),node=action.path("elementRef").asText();var spec=view.definition.view().node(node).orElseThrow(()->new IllegalArgumentException("TARGET_NOT_FOUND"));
        if(secret(spec)||!view.definition.view().interactiveNode(node,view.content.data()))throw new IllegalStateException("NOT_INTERACTABLE");
        String value="";switch(name){case "click"->{if(!spec.path("events").has("click"))throw new IllegalArgumentException("NOT_INTERACTABLE");}case "fill"->{if(!spec.path("type").asText().equals("input")||!action.path("value").isTextual()||action.path("value").asText().length()>8192)throw new IllegalArgumentException("NOT_INTERACTABLE");value=action.path("value").asText();}case "toggle"->{if(!spec.path("type").asText().equals("toggle")||!action.path("checked").isBoolean())throw new IllegalArgumentException("NOT_INTERACTABLE");value=action.path("checked").asText();}case "scroll"->{var element=view.content.rendered().node(node);if(!(element instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroller))throw new IllegalArgumentException("NOT_INTERACTABLE");double delta=action.path("deltaY").asDouble();if(!Double.isFinite(delta)||Math.abs(delta)>10000)throw new IllegalArgumentException("SCROLL_RANGE");scroller.verticalScroller.scrollByWheel(-delta);return Map.of("status","APPLIED","businessVerified",false,"inputMode","LDLIB2");}default->throw new IllegalArgumentException("UNSUPPORTED");}
        view.agentDispatch=true;try{event(view,node,name.equals("click")?"click":"change",value);}finally{view.agentDispatch=false;}
        if(!view.error.isBlank())throw new IllegalStateException(view.error);return Map.of("status","APPLIED","businessVerified",false,"inputMode","LDLIB2");
    }

    public static void tick(){thread();context();tick++;for(var view:List.copyOf(VIEWS.values())){if(view.window!=null&&view.window.closed()){close(view.id);continue;}boolean shown=visible(view);if(shown!=view.observedVisibility){view.observedVisibility=shown;PackageContentClient.visibility(view.id,shown);}if(!current(view)||!view.ready||!shown||view.blocked)continue;for(var entry:view.definition.reads().entrySet()){var request=entry.getValue();if(tick<view.nextRead.getOrDefault(entry.getKey(),0L))continue;view.nextRead.put(entry.getKey(),request.intervalTicks()==0?Long.MAX_VALUE:tick+request.intervalTicks());dispatch(view,entry.getKey(),request,view.content.data(),false);}}}
    private static void context(){var mc=Minecraft.getInstance();if(connection!=mc.getConnection()||level!=mc.level){for(String id:List.copyOf(VIEWS.keySet()))close(id);connection=mc.getConnection();level=mc.level;}}
    public static void clear(){thread();for(String id:List.copyOf(VIEWS.keySet()))close(id);}
    private static void thread(){if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("NATIVE_PACKAGE_CLIENT_THREAD");}
    private NativePackageViews(){}
}
