package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.*;
import dev.mineagent.runtime.core.ui.dynamic.InterfaceDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Shared LDLib2 controls. Built-in F2/Ctrl+M screens have no KubeJS dependency. */
public final class LdInterfaceRenderer {
    private static final ObjectMapper JSON=new ObjectMapper();
    @FunctionalInterface public interface Events {void dispatch(String node,String event,String value);}
    private LdInterfaceRenderer(){}
    public static Rendered build(InterfaceDefinition definition,Map<String,JsonNode> data,Events events) throws Exception {
        if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("INTERFACE_CLIENT_THREAD_REQUIRED");
        var bridge=new BuilderBridge(definition,data,events);
        try{bridge.finish(buildNode(definition.root(),bridge));return bridge.result();}
        catch(Exception failure){if(bridge.result!=null)bridge.result.close();throw failure;}
    }
    private static UIElement buildNode(JsonNode node,BuilderBridge bridge) throws Exception {
        var element=bridge.create(node.toString());
        for(var child:node.path("children"))bridge.add(element,buildNode(child,bridge));
        String id=node.path("id").asText(),type=node.path("type").asText();
        if(type.equals("input")||type.equals("toggle"))bridge.listen(element,"change",value->bridge.dispatch(id,"change",value));
        if(node.path("events").has("click"))bridge.listen(element,"click",value->bridge.dispatch(id,"click",value));
        return element;
    }
    public static final class BuilderBridge {
        private final InterfaceDefinition definition;
        private final Map<String,JsonNode> data;
        private final Events events;
        private final Map<String,UIElement> nodes=new LinkedHashMap<>();
        private final Map<String,JsonNode> specs=new LinkedHashMap<>();
        private Rendered result;
        public Rendered result(){return result;}
        private boolean ready;
        BuilderBridge(InterfaceDefinition definition,Map<String,JsonNode> data,Events events){this.definition=definition;this.data=data;this.events=events;}
        public UIElement create(String json) throws Exception {
            JsonNode n=JSON.readTree(json);String id=n.path("id").asText(),type=n.path("type").asText();
            UIElement element=switch(type){
                case "label"->new TextElement();case "button"->new Button();case "input"->new TextField();
                case "toggle"->new Toggle();case "progress"->new ProgressBar();case "scroll"->new ScrollerView();
                case "row","column","panel","image"->new UIElement();default->throw new IllegalArgumentException("INTERFACE_WIDGET_TYPE: "+type);
            };
            element.setId(id);nodes.put(id,element);specs.put(id,n);
            if(element instanceof TextField field)field.textFieldStyle(style->style.placeholder(Component.empty()));
            if(element instanceof ProgressBar progress){progress.label.setText(Component.literal(n.path("text").asText("")));progress.label.setDisplay(n.has("text"));}
            if(type.equals("row")||type.equals("column"))element.addLocalStylesheet(strictStyles("#"+id+" { flex-direction: "+type+"; }"));
            if(n.has("style"))element.addLocalStylesheet(strictStyles("#"+id+" { "+n.get("style").asText()+" }"));
            for(var cls:n.path("classes"))element.addClass(cls.asText());
            element.setDisplay(InterfaceDefinition.boundBoolean(n,"visible",data));element.setActive(InterfaceDefinition.boundBoolean(n,"enabled",data));
            if(type.equals("image")){
                if(!n.has("resource"))throw new IllegalArgumentException("INTERFACE_IMAGE_RESOURCE: "+id);
                var resource=net.minecraft.resources.Identifier.parse(n.get("resource").asText());
                if(Minecraft.getInstance().getResourceManager().getResource(resource).isEmpty())throw new IllegalArgumentException("INTERFACE_RESOURCE_MISSING: "+resource);
                element.getStyle().backgroundTexture(SpriteTexture.of(resource));
            }
            if(element instanceof Toggle toggle)toggle.setText(Component.literal(n.path("text").asText("")));
            set(element,value(n,data));return element;
        }
        public void add(UIElement parent,UIElement child){if(parent instanceof ScrollerView scroller)scroller.addScrollViewChild(child);else parent.addChild(child);}
        public void listen(UIElement element,String event,Consumer<String> listener){
            if(event.equals("change")){
                if(element instanceof TextField input)input.registerValueListener(value->{if(ready)listener.accept(value);});
                else if(element instanceof Toggle toggle)toggle.registerValueListener(value->{if(ready)listener.accept(value.toString());});
            }else if(element instanceof Button button)button.setOnClick(e->{if(ready)listener.accept("");});
            else element.addEventListener(UIEvents.MOUSE_DOWN,e->{if(ready)listener.accept("");});
        }
        public void dispatch(String node,String event,String value){if(ready)events.dispatch(node,event,value);}
        public void finish(UIElement root){
            ModularUI ui;
            if(definition.surface()==InterfaceDefinition.Surface.HUD){
                var canvas=new UIElement();canvas.getLayout().widthPercent(100).heightPercent(100);root.addClass("native_hud_root");canvas.addChild(root);
                ui=new ModularUI(UI.of(canvas,List.of(strictStyles(".native_hud_root { position: absolute; left: 8; top: 8; }"),strictStyles(definition.stylesheet())),size->size),Minecraft.getInstance().player);
            }else ui=new ModularUI(UI.of(root,List.of(strictStyles(definition.stylesheet()))),Minecraft.getInstance().player);
            result=new Rendered(ui,nodes,specs,this);
            var window=Minecraft.getInstance().getWindow();
            ui.init(window.getGuiScaledWidth(),window.getGuiScaledHeight());
            ready=true;
        }
    }
    public static final class Rendered implements AutoCloseable {
        public final ModularUI ui;
        private final Map<String,UIElement> nodes;
        private final Map<String,JsonNode> specs;
        private final BuilderBridge bridge;
        private boolean closed;
        Rendered(ModularUI ui,Map<String,UIElement> nodes,Map<String,JsonNode> specs,BuilderBridge bridge){this.ui=ui;this.nodes=Map.copyOf(nodes);this.specs=Map.copyOf(specs);this.bridge=bridge;}
        public void update(Map<String,JsonNode> data){
            if(closed)throw new IllegalStateException("INTERFACE_RENDERER_CLOSED");
            record Update(JsonNode value,boolean visible,boolean enabled){}
            var updates=new LinkedHashMap<UIElement,Update>();
            for(var e:specs.entrySet())if(e.getValue().has("bind")||e.getValue().has("bindings")){
                var node=nodes.get(e.getKey());var value=value(e.getValue(),data);validateValue(node,value);updates.put(node,new Update(value,InterfaceDefinition.boundBoolean(e.getValue(),"visible",data),InterfaceDefinition.boundBoolean(e.getValue(),"enabled",data)));
            }
            // Validate all bindings before mutating any control; setters never notify server-originated changes.
            bridge.ready=false;
            try{updates.forEach((node,update)->{set(node,update.value);node.setDisplay(update.visible);node.setActive(update.enabled);});}finally{bridge.ready=true;}
        }
        public UIElement node(String id){return nodes.get(id);}
        @Override public void close(){if(!closed){closed=true;bridge.ready=false;if(!ui.isRemoved())ui.onRemoved();}}
    }
    private static JsonNode value(JsonNode spec,Map<String,JsonNode> data){
        var bindings=spec.path("bindings");if(bindings.has("value")||bindings.has("text"))return dev.mineagent.runtime.core.ui.dynamic.InterfaceExpression.evaluate(bindings.get(bindings.has("value")?"value":"text"),data);
        JsonNode fallback=spec.has("value")?spec.get("value"):switch(spec.path("type").asText()){
            case "toggle"->com.fasterxml.jackson.databind.node.BooleanNode.FALSE;
            case "progress"->com.fasterxml.jackson.databind.node.IntNode.valueOf(0);
            default->spec.path("text");
        };
        return data.getOrDefault(spec.path("bind").asText(),fallback);
    }
    /** LDLib's permissive parser logs and skips bad declarations; dynamic revisions must fail instead. */
    private static Stylesheet strictStyles(String source){
        var rules=new ArrayList<StyleRule>();var matcher=Stylesheet.RULE.matcher(source);int end=0;
        while(matcher.find()){
            if(!source.substring(end,matcher.start()).isBlank())throw new IllegalArgumentException("INTERFACE_LSS_SYNTAX at "+end);
            var properties=new LinkedHashMap<Property<?>,StyleValue<?>>();String block=matcher.group(2);
            var declarations=Stylesheet.DECL.matcher(block);int declarationEnd=0;
            while(declarations.find()){
                if(!block.substring(declarationEnd,declarations.start()).isBlank())throw new IllegalArgumentException("INTERFACE_LSS_DECLARATION: "+matcher.group(1));
                String name=declarations.group(1),raw=declarations.group(2).trim();var property=PropertyRegistry.byName(name);
                if(property==null)throw new IllegalArgumentException("INTERFACE_LSS_UNKNOWN_PROPERTY: "+name+" at "+matcher.group(1));
                try{var parsed=Objects.requireNonNull(property.valueParser.parse(raw));Objects.requireNonNull(parsed.compute());properties.put(property,parsed);}
                catch(Exception e){throw new IllegalArgumentException("INTERFACE_LSS_INVALID_VALUE: "+name+"="+raw+" at "+matcher.group(1),e);}
                declarationEnd=declarations.end();
            }
            if(!block.substring(declarationEnd).isBlank())throw new IllegalArgumentException("INTERFACE_LSS_DECLARATION: "+matcher.group(1));
            for(String selector:matcher.group(1).trim().split(","))rules.add(new StyleRule(HierarchicalStyleMatcher.parse(selector.trim()),Map.copyOf(properties)));
            end=matcher.end();
        }
        if(!source.substring(end).isBlank())throw new IllegalArgumentException("INTERFACE_LSS_SYNTAX at "+end);
        return new Stylesheet(rules);
    }
    private static void validateValue(UIElement element,JsonNode value){
        if(value.isMissingNode()||value.isNull())return;
        if(element instanceof ProgressBar&&(!value.isNumber()||!Double.isFinite(value.doubleValue())||value.doubleValue()<0||value.doubleValue()>1))throw new IllegalArgumentException("INTERFACE_PROGRESS_VALUE: "+element.getId());
        if(element instanceof Toggle&&!value.isBoolean())throw new IllegalArgumentException("INTERFACE_TOGGLE_VALUE: "+element.getId());
        if((element instanceof TextField||element instanceof TextElement||element instanceof Button)&&!value.isValueNode())throw new IllegalArgumentException("INTERFACE_TEXT_VALUE: "+element.getId());
    }
    private static void set(UIElement element,JsonNode value){
        validateValue(element,value);String text=value.isMissingNode()||value.isNull()?"":value.asText();
        if(element instanceof TextElement label)label.setText(Component.literal(text));
        else if(element instanceof Button button)button.setText(Component.literal(text));
        else if(element instanceof TextField input){if(!input.getValue().equals(text))input.setText(text,false);}
        else if(element instanceof Toggle toggle)toggle.setOn(value.asBoolean(false),false);
        else if(element instanceof ProgressBar progress)progress.setProgress((float)value.asDouble(0));
    }
}
