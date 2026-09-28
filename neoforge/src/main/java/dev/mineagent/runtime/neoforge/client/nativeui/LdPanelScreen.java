package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.mineagent.runtime.neoforge.mixin.client.PanelEditBoxAccess;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Existing Java panel controllers feed LDLib2 controls; no vanilla widget is rendered or receives input. */
public abstract class LdPanelScreen extends Screen {
    private final List<AbstractWidget> controls=new ArrayList<>();
    private final Set<EditBox> secrets=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<AbstractWidget,UIElement> elements=new IdentityHashMap<>();
    private ModularUI ui;private AbstractWidget requestedFocus,focusedController;private boolean dirty=true,syncing;
    protected LdPanelScreen(Component title){super(title);}
    protected final void secret(EditBox field){secrets.add(field);dirty=true;}
    protected final GuiEventListener controllerFocus(){return focusedController;}
    @Override protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget){
        if(widget instanceof AbstractWidget control){controls.add(control);dirty=true;return widget;}
        return super.addRenderableWidget(widget);
    }
    @Override protected void clearWidgets(){
        super.clearWidgets();if(ui!=null&&!ui.isRemoved())ui.onRemoved();ui=null;controls.clear();secrets.clear();elements.clear();focusedController=null;dirty=true;
    }
    @Override public void setFocused(GuiEventListener listener){
        if(listener instanceof AbstractWidget control&&controls.contains(control)){
            requestedFocus=control;if(ui!=null&&elements.containsKey(control))ui.requestFocus(elements.get(control));return;
        }
        super.setFocused(listener);
    }
    private boolean accepts(AbstractWidget control){return minecraft!=null&&minecraft.screen==this&&controls.contains(control)&&control.active&&control.visible;}
    private UIElement create(AbstractWidget control){
        UIElement element;
        if(control instanceof EditBox input){
            var field=new TextField();field.setText(input.getValue(),false);field.textFieldStyle(s->s.placeholder(input.getMessage()));
            if(secrets.contains(input))field.setFormatter(value->Component.literal("•".repeat(value.length())));
            field.registerValueListener(value->{if(!syncing&&accepts(input)&&((PanelEditBoxAccess)input).mineagent$isEditable()){input.setValue(value);if(!field.getValue().equals(input.getValue()))field.setText(input.getValue(),false);input.setCursorPosition(Math.min(field.getCursorPos(),input.getValue().length()));}});
            element=field;
        }else if(control instanceof MultiLineEditBox input){
            var area=new TextArea();area.setValue(input.getValue().split("\n",-1),false);
            area.registerValueListener(value->{if(!syncing&&accepts(input))input.setValue(String.join("\n",value));});element=area;
        }else if(control instanceof Checkbox checkbox){
            var toggle=new Toggle().setText(checkbox.getMessage());toggle.setOn(checkbox.selected(),false);
            toggle.registerValueListener(value->{if(!syncing&&accepts(checkbox)&&value!=checkbox.selected())checkbox.onPress(new KeyEvent(257,0,0));});element=toggle;
        }else if(control instanceof AbstractButton button){
            var nativeButton=new Button().setText(button.getMessage());nativeButton.setOnClick(event->{if(accepts(button))button.onPress(new KeyEvent(257,0,event.modifiers));});element=nativeButton;
        }else if(control instanceof StringWidget){
            element=new TextElement().setText(control.getMessage()).textStyle(s->s.adaptiveWidth(false).adaptiveHeight(false).textColor(control.getFGColor()));
        }else throw new IllegalArgumentException("NATIVE_PANEL_UNSUPPORTED_CONTROL: "+control.getClass().getName());
        element.addEventListener(UIEvents.FOCUS_IN,e->{focusedController=control;if(control instanceof EditBox input&&element instanceof TextField field)input.setCursorPosition(Math.min(field.getCursorPos(),input.getValue().length()));});
        element.addEventListener(UIEvents.FOCUS_OUT,e->{if(focusedController==control)focusedController=null;});
        return element;
    }
    private void mount(){
        if(!dirty)return;dirty=false;
        if(ui!=null){super.clearWidgets();if(!ui.isRemoved())ui.onRemoved();}
        var root=new UIElement();root.getLayout().widthPercent(100).heightPercent(100);root.getStyle().backgroundTexture(NativeUiTheme.panel());elements.clear();
        for(var control:controls){var element=create(control);elements.put(control,element);root.addChild(element);}
        NativeUiTheme.controls(root);ui=new ModularUI(NativeUiTheme.ui(root),minecraft.player);sync();ModularUIClientAccess.setScreenAndInit(ui,this);
        super.addRenderableWidget(ModularUIClientAccess.getWidget(ui));super.setFocused(ModularUIClientAccess.getWidget(ui));
        if(requestedFocus!=null&&elements.containsKey(requestedFocus)){ui.requestFocus(elements.get(requestedFocus));requestedFocus=null;}
    }
    private void sync(){
        syncing=true;try{
            for(var control:List.copyOf(controls)){
                var element=elements.get(control);if(element==null)continue;
                element.getLayout().positionType(TaffyPosition.ABSOLUTE).left(control.getX()).top(control.getY()).width(control.getWidth()).height(control.getHeight());
                element.setDisplay(control.visible);element.setActive(control.active&&(!(control instanceof EditBox input)||((PanelEditBoxAccess)input).mineagent$isEditable()));
                if(control instanceof EditBox input&&element instanceof TextField field){if(!field.getValue().equals(input.getValue()))field.setText(input.getValue(),false);}
                else if(control instanceof MultiLineEditBox input&&element instanceof TextArea area){if(!String.join("\n",area.getValue()).equals(input.getValue()))area.setValue(input.getValue().split("\n",-1),false);}
                else if(control instanceof Checkbox checkbox&&element instanceof Toggle toggle){toggle.setOn(checkbox.selected(),false);toggle.setText(control.getMessage());}
                else if(element instanceof Button button)button.setText(control.getMessage());
                else if(element instanceof TextElement text)text.setText(control.getMessage());
            }
        }finally{syncing=false;}
    }
    @Override public void tick(){mount();sync();super.tick();}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick){mount();sync();super.extractRenderState(graphics,mouseX,mouseY,partialTick);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){super.removed();for(var secret:secrets)secret.setValue("");if(ui!=null&&!ui.isRemoved())ui.onRemoved();}
}
