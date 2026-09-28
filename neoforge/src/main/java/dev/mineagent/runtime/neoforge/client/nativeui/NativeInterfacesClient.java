package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import dev.mineagent.runtime.core.ui.dynamic.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** One native view per world/owner/AI/id, with independent revisions and no browser lifecycle. */
public final class NativeInterfacesClient {
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Key(UUID world,UUID owner,UUID agent,String id){}
    private static final class Slot {
        final Key key;final InterfaceSession<LdInterfaceRenderer.Rendered> session;
        Map<String,String> sourceErrors=Map.of();final ArrayDeque<Map<String,Object>> events=new ArrayDeque<>();long wireRevision;String activationToken="",error="";NativeScreen screen;
        Slot(Key key,long revision){this.key=key;wireRevision=revision;session=new InterfaceSession<>(new InterfaceSession.Scope(key.world,key.owner,key.agent,connectionId,key.id));}
    }
    private static final Map<Key,Slot> VIEWS=new LinkedHashMap<>();
    private static UUID restoreRequest;private static boolean restoreReady;private static long nextRestore;
    public static void restoreAcknowledged(UUID id){if(id.equals(restoreRequest))restoreReady=true;}
    private static Object connection,level;private static UUID connectionId=UUID.randomUUID();
    public static void tick(){
        var mc=Minecraft.getInstance();if(connection!=mc.getConnection()||level!=mc.level){restoreReady=false;restoreRequest=null;nextRestore=0;
        for(var slot:VIEWS.values())slot.session.close();VIEWS.clear();connection=mc.getConnection();level=mc.level;connectionId=UUID.randomUUID();
        if(mc.screen instanceof NativeScreen screen){screen.detach();mc.setScreen(null);}
        }
        if(mc.player!=null&&mc.level!=null&&mc.getConnection()!=null&&!restoreReady&&System.currentTimeMillis()>=nextRestore){nextRestore=System.currentTimeMillis()+5000;restoreRequest=UUID.randomUUID();ClientPacketDistributor.sendToServer(new UiPayloads.Command(restoreRequest,"nativeInterfaceReady","{}"));}
    }
    public static void accept(UiPayloads.Event packet){
        tick();var mc=Minecraft.getInstance();Map<String,Object> reply;boolean changed=false;
        try{
            var args=JSON.readTree(packet.json());UUID owner=UUID.fromString(args.path("owner").asText()),world=UUID.fromString(args.path("world").asText()),agent=UUID.fromString(args.path("agent").asText());
            if(mc.player==null||mc.level==null||!owner.equals(mc.player.getUUID())||!args.path("dimension").asText().equals(mc.level.dimension().identifier().toString()))throw new IllegalStateException("NATIVE_UI_CONTEXT_CHANGED");
            String kind=args.path("kind").asText();
            if(kind.equals("inspect")){
                var views=new ArrayList<Map<String,Object>>();for(var slot:VIEWS.values())if(slot.key.world.equals(world)&&slot.key.owner.equals(owner)&&slot.key.agent.equals(agent)&&(!args.has("id")||slot.key.id.equals(args.get("id").asText())))views.add(snapshot(slot));
                reply=Map.of("status","OBSERVED","views",views,"ldlib2",true,"kubejs",net.neoforged.fml.ModList.get().isLoaded("kubejs"));
            }else if(kind.equals("restore")){
                String id=args.path("id").asText();long revision=args.path("revision").asLong();if(revision<1)throw new IllegalArgumentException("NATIVE_UI_REVISION");var key=new Key(world,owner,agent,id);var present=VIEWS.get(key);
                if(present!=null){reply=new LinkedHashMap<>(snapshot(present));reply.put("status","OBSERVED");}
                else{
                    if(!net.neoforged.fml.ModList.get().isLoaded("kubejs"))throw new IllegalStateException("NATIVE_UI_KUBEJS_REQUIRED");
                    var source=(ObjectNode)JSON.readTree(args.path("source").asText());source.set("data",args.path("data"));if(InterfaceDefinition.parse(source.toString()).surface()!=InterfaceDefinition.Surface.HUD)throw new IllegalArgumentException("NATIVE_UI_RESTORE_PASSIVE_ONLY");var slot=new Slot(key,revision);long renderedRevision=1;
                    var built=slot.session.replace(slot.session.scope(),0,source.toString(),(definition,data)->KubeInterfaceRenderer.build(definition,data,(node,event,value)->handle(slot,renderedRevision,node,event,value)));if(!built.applied())throw new IllegalArgumentException(built.error());
                    changed=true;slot.session.interactive(false);VIEWS.put(key,slot);LdHudRegistry.attach(slot.session,slot.session.definition().order());reply=new LinkedHashMap<>(snapshot(slot));reply.put("status","APPLIED");
                }
            }else if(kind.equals("feed")||kind.equals("revoke")){
                var key=new Key(world,owner,agent,args.path("id").asText());var slot=VIEWS.get(key);if(slot==null||slot.wireRevision!=args.path("expectedRevision").asLong(-1))throw new IllegalStateException("NATIVE_UI_STALE_CLIENT_REVISION");
                if(kind.equals("revoke")){if(slot.screen!=null&&mc.screen==slot.screen){slot.screen.detach();mc.setScreen(null);}slot.session.close();VIEWS.remove(key);reply=Map.of("status","APPLIED");}
                else{var values=new LinkedHashMap<String,JsonNode>();args.path("data").properties().forEach(e->values.put(e.getKey(),e.getValue()));if(!slot.session.definition().sources().keySet().containsAll(values.keySet()))throw new IllegalArgumentException("NATIVE_UI_SOURCE_KEY");var applied=slot.session.patch(slot.session.scope(),slot.session.revision(),slot.session.dataRevision(),values,data->update(slot,data));if(!applied.applied())throw new IllegalArgumentException(applied.error());var errors=new LinkedHashMap<String,String>();args.path("sourceErrors").properties().forEach(e->errors.put(e.getKey(),e.getValue().asText()));slot.sourceErrors=Map.copyOf(errors);reply=Map.of("status","APPLIED","revision",slot.wireRevision,"dataRevision",slot.session.dataRevision());}
            }else{
                if(!Set.of("replace","data","show","hide","interact","release").contains(kind))throw new IllegalArgumentException("NATIVE_UI_ACTION");
                String id=args.path("id").asText();long expected=args.path("expectedRevision").asLong(-1),revision=args.path("revision").asLong(-1);
                if(expected<0||revision!=expected+1)throw new IllegalArgumentException("NATIVE_UI_REVISION");
                String activationToken=UUID.fromString(args.path("activationToken").asText()).toString();
                var key=new Key(world,owner,agent,id);var slot=VIEWS.get(key);boolean fresh=slot==null;
                if(fresh)slot=new Slot(key,expected);
                boolean wasVisible=fresh||slot.session.visible(),wasInteractive=!fresh&&slot.session.interactive();
                if(slot.wireRevision!=expected)throw new IllegalStateException("NATIVE_UI_STALE_CLIENT_REVISION");
                var values=new LinkedHashMap<String,JsonNode>();args.path("data").properties().forEach(e->values.put(e.getKey(),e.getValue()));
                if(kind.equals("replace")||fresh||slot.session.rendered()==null||slot.session.rendered().ui.isRemoved()){
                    if(!net.neoforged.fml.ModList.get().isLoaded("kubejs"))throw new IllegalStateException("NATIVE_UI_KUBEJS_REQUIRED");
                    String source=args.path("source").asText();JsonNode definition=JSON.readTree(source);((ObjectNode)definition).set("data",JSON.valueToTree(values));
                    final Slot target=slot;long targetRevision=slot.session.revision()+1;
                    var built=slot.session.replace(slot.session.scope(),slot.session.revision(),definition.toString(),(d,data)->KubeInterfaceRenderer.build(d,data,(node,event,value)->handle(target,targetRevision,node,event,value)));
                    if(!built.applied()){slot.error=built.error();throw new IllegalArgumentException(built.error());}
                    changed=true;
                    if(slot.screen!=null&&mc.screen==slot.screen){slot.screen.detach();mc.setScreen(null);}
                    VIEWS.put(key,slot);
                    if(fresh&&!Set.of("replace","show","interact").contains(kind))slot.session.visible(false);
                }else if(kind.equals("data")){
                    if(args.has("patch")){values.clear();args.get("patch").properties().forEach(e->values.put(e.getKey(),e.getValue()));}
                    final Slot target=slot;var receipt=slot.session.patch(slot.session.scope(),slot.session.revision(),slot.session.dataRevision(),values,data->update(target,data));
                    if(!receipt.applied())throw new IllegalArgumentException(receipt.error());
                    changed=true;
                }
                boolean hud=slot.session.definition().surface()==InterfaceDefinition.Surface.HUD;
                // From this point screen/HUD activation can have side effects even for control-only operations.
                changed=true;
                switch(kind){
                    case "replace"->{slot.session.visible(wasVisible);if(wasVisible){if(hud&&wasInteractive)open(slot,true);else if(hud){slot.session.interactive(false);LdHudRegistry.attach(slot.session,slot.session.definition().order());}else open(slot,false);}}
                    case "show"->{slot.session.visible(true);if(hud){slot.session.interactive(false);LdHudRegistry.attach(slot.session,slot.session.definition().order());}else open(slot,false);}
                    case "hide"->{slot.session.visible(false);if(slot.screen!=null&&mc.screen==slot.screen){slot.screen.detach();mc.setScreen(null);}slot.session.interactive(false);}
                    case "interact"->{open(slot,hud);slot.session.visible(true);}
                    case "release"->{if(slot.screen!=null&&mc.screen==slot.screen)mc.setScreen(null);slot.session.interactive(false);if(!hud)slot.session.visible(false);}
                    case "data"->{}
                    default->throw new IllegalArgumentException("NATIVE_UI_ACTION");
                }
                if(hud&&slot.session.visible()&&!slot.session.interactive())LdHudRegistry.attach(slot.session,slot.session.definition().order());
                slot.wireRevision=revision;slot.activationToken=activationToken;slot.error="";reply=new LinkedHashMap<>(snapshot(slot));reply.put("status","APPLIED");
            }
        }catch(Exception|LinkageError error){reply=Map.of("status",changed?"UNKNOWN":"REJECTED","error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));}
        String encoded;try{encoded=JSON.writeValueAsString(reply);if(encoded.length()>120000)encoded="{\"status\":\"UNKNOWN\",\"error\":\"NATIVE_UI_RECEIPT_SIZE\"}";}catch(Exception e){encoded="{\"status\":\"UNKNOWN\",\"error\":\"NATIVE_UI_RECEIPT\"}";}
        if(mc.getConnection()!=null)ClientPacketDistributor.sendToServer(new UiPayloads.Command(packet.requestId(),"nativeInterfaceReply",encoded));
    }
    private static Map<String,Object> snapshot(Slot slot){var out=new LinkedHashMap<String,Object>();out.put("id",slot.key.id);out.put("revision",slot.wireRevision);out.put("activationToken",slot.activationToken);out.put("dataRevision",slot.session.dataRevision());out.put("visible",slot.session.visible());out.put("interactive",slot.session.interactive());out.put("data",slot.session.data());out.put("events",List.copyOf(slot.events));out.put("error",slot.error);out.put("sourceErrors",slot.sourceErrors);return out;}
    static com.lowdragmc.lowdraglib2.gui.ui.UIElement smokeWidget(String id,String node){
        if(!Boolean.getBoolean("mineagent.nativeUiSmoke"))throw new IllegalStateException("SMOKE_DISABLED");
        var slot=VIEWS.values().stream().filter(s->s.key.id.equals(id)).findFirst().orElseThrow();
        return slot.screen!=null&&Minecraft.getInstance().screen==slot.screen?slot.screen.rendered.node(node):slot.session.rendered().node(node);
    }
    private static void open(Slot slot,boolean projection)throws Exception{
        var mc=Minecraft.getInstance();long revision=slot.session.revision();
        var rendered=projection?KubeInterfaceRenderer.build(slot.session.definition(),slot.session.data(),(node,event,value)->handle(slot,revision,node,event,value)):slot.session.rendered();
        if(slot.screen!=null&&mc.screen==slot.screen){slot.screen.detach();mc.setScreen(null);}
        slot.session.interactive(true);
        slot.screen=new NativeScreen(slot,rendered,projection);mc.setScreen(slot.screen);
    }
    private static void update(Slot slot,Map<String,JsonNode> values){
        slot.session.rendered().update(values);if(slot.screen!=null&&Minecraft.getInstance().screen==slot.screen&&slot.screen.projection)slot.screen.rendered.update(values);
    }
    private static void handle(Slot slot,long revision,String node,String event,String value){
        try{
            if(!VIEWS.containsValue(slot)||Minecraft.getInstance().screen!=slot.screen)throw new IllegalStateException("NATIVE_UI_NOT_INTERACTING");
            var actions=slot.session.actions(slot.session.scope(),revision,node,event);
            if(event.equals("change")){
                var spec=slot.session.definition().node(node).orElseThrow();if(spec.has("bind"))slot.session.input(slot.session.scope(),revision,node,spec.path("type").asText().equals("toggle")?BooleanNode.valueOf(Boolean.parseBoolean(value)):TextNode.valueOf(value),data->update(slot,data));
            }
            var patch=new LinkedHashMap<String,JsonNode>();var data=slot.session.data();
            for(var action:actions){String op=action.path("op").asText(),key=action.path("key").asText();
                if(op.equals("set")){var spec=slot.session.definition().node(node).orElseThrow();var v=action.has("expr")?InterfaceExpression.evaluate(action.get("expr"),data):action.has("from")?spec.path("type").asText().equals("toggle")?BooleanNode.valueOf(Boolean.parseBoolean(value)):TextNode.valueOf(value):action.get("value");patch.put(key,v);data.put(key,v);}
                else if(op.equals("toggle")){var v=BooleanNode.valueOf(!data.getOrDefault(key,BooleanNode.FALSE).asBoolean());patch.put(key,v);data.put(key,v);}
                else if(op.equals("emit")){slot.events.addLast(Map.of("eventId",UUID.randomUUID().toString(),"node",node,"event",event,"action",action.path("action").asText(),"args",action.path("args").deepCopy(),"revision",slot.wireRevision,"state","INTENT_NOT_EXECUTED"));while(slot.events.size()>64)slot.events.removeFirst();}
            }
            if(!patch.isEmpty()){var receipt=slot.session.localData(slot.session.scope(),revision,patch,v->update(slot,v));if(!receipt.applied())throw new IllegalArgumentException(receipt.error());}
            else update(slot,slot.session.data());
        }catch(Exception error){slot.error=Objects.toString(error.getMessage(),"NATIVE_UI_EVENT_FAILED");try{update(slot,slot.session.data());}catch(Exception ignored){}}
    }
    private static final class NativeScreen extends ModularUIScreen {
        final Slot slot;final LdInterfaceRenderer.Rendered rendered;final boolean projection;
        NativeScreen(Slot slot,LdInterfaceRenderer.Rendered rendered,boolean projection){super(rendered.ui,Component.literal(slot.session.definition().title()));this.slot=slot;this.rendered=rendered;this.projection=projection;}
        void detach(){clearWidgets();if(projection)rendered.close();}
        @Override public void onClose(){slot.session.interactive(false);if(!projection)slot.session.visible(false);super.onClose();}
    }
    private NativeInterfacesClient(){}
}
