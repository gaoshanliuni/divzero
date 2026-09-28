package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Transform2D;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.lowdragmc.lowdraglib2.gui.ui.style.*;
import com.lowdragmc.lowdraglib2.gui.util.UISoundUtils;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Release-to-activate gestures for DivZero trees, including LDLib2 dialog buttons. */
public final class NativeButtonFeedback {
    public static final String ROOT_CLASS="divzero-owned-ui";
    public interface Access {
        NativeButtonFeedback divzero$feedback();
        UIEventListener divzero$clickHandler();
        void divzero$buttonState(Button.State state);
    }
    private static final java.util.Set<NativeButtonFeedback> HELD=new java.util.HashSet<>();
    public static void windowFocusLost(){for(var gesture:java.util.List.copyOf(HELD))gesture.cancel(true);}
    private final Button button;
    private final Access access;
    private boolean mouseArmed;
    private int keyArmed=-1;
    private UIElement releaseRoot;
    private ISubscription animation;
    private Transform2D restingText;
    private final UIEventListener outsideRelease=event->{if(event.button==0&&!contains(event.target))cancel(false);};

    public NativeButtonFeedback(Button button){
        this.button=button;access=(Access)button;button.setFocusable(true);
        button.addEventListener(UIEvents.KEY_DOWN,this::keyDown);
        button.addEventListener(UIEvents.KEY_UP,this::keyUp);
        button.addEventListener(UIEvents.FOCUS_OUT,event->cancel(false));
        button.addEventListener(UIEvents.REMOVED,event->cancel(true));
        button.addEventListener(UIEvents.MUI_CHANGED,event->cancel(true));
        button.addEventListener(UIEvents.STYLE_CHANGED,event->{if(armed()&&!usable())cancel(true);});
        button.addEventListener(UIEvents.TICK,event->{
            if(armed()&&(!usable()||!Minecraft.getInstance().isWindowActive()
                    ||mouseArmed&&button.getModularUI().getLastMouseDownButton()!=0
                    ||keyArmed>=0&&!button.isFocused()))cancel(true);
        });
    }
    public static boolean owns(Button button){for(UIElement node=button;node!=null;node=node.getParent())if(node.hasClass(ROOT_CLASS))return true;return false;}
    public static void install(Button button){if(owns(button)&&button instanceof Access access)access.divzero$feedback();}
    private boolean armed(){return mouseArmed||keyArmed>=0;}
    private boolean contains(UIElement target){for(var node=target;node!=null;node=node.getParent())if(node==button)return true;return false;}
    private boolean usable(){
        var ui=button.getModularUI();if(ui==null||ui.isRemoved())return false;
        for(UIElement node=button;node!=null;node=node.getParent())if(!node.isActive()||!node.isDisplayed()||!node.isVisible()||node.getStyle().opacity()<=0)return false;
        return true;
    }
    public void mouseDown(UIEvent event){
        event.stopPropagation();if(event.button!=0||!usable()||!Minecraft.getInstance().isWindowActive())return;
        cancel(false);button.getModularUI().requestFocus(button);mouseArmed=true;HELD.add(this);
        releaseRoot=button;while(releaseRoot.getParent()!=null)releaseRoot=releaseRoot.getParent();
        releaseRoot.addEventListener(UIEvents.MOUSE_UP,outsideRelease,true);visual(Button.State.PRESSED,false);
    }
    public void mouseUp(UIEvent event){
        event.stopPropagation();if(event.button!=0||!mouseArmed)return;
        boolean activate=mouseArmed&&usable()&&Minecraft.getInstance().isWindowActive()&&button.isMouseOver(event.x,event.y);
        mouseArmed=false;HELD.remove(this);detachRelease();visual(usable()&&button.isMouseOver(event.x,event.y)?Button.State.HOVERED:Button.State.DEFAULT,false);
        if(activate)activate(event);
    }
    public void mouseEnter(UIEvent event){
        if(!usable())return;
        if(mouseArmed&&button.getModularUI().getLastMouseDownButton()!=0)cancel(false);
        visual(armed()?Button.State.PRESSED:Button.State.HOVERED,false);
    }
    public void mouseLeave(UIEvent event){
        // Leaving a text child is not leaving the button. Keep the original press armed while dragging.
        if(contains(event.relatedTarget)||button.isMouseOver(event.x,event.y))return;
        visual(keyArmed>=0?Button.State.PRESSED:Button.State.DEFAULT,false);
    }
    private static boolean activationKey(int key){return key==GLFW.GLFW_KEY_SPACE||key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER;}
    private void keyDown(UIEvent event){
        if(!activationKey(event.keyCode)||!button.isFocused()||!usable())return;
        event.stopPropagation();if(!armed()){keyArmed=event.keyCode;HELD.add(this);visual(Button.State.PRESSED,false);}
    }
    private void keyUp(UIEvent event){
        if(event.keyCode!=keyArmed)return;event.stopPropagation();
        boolean activate=button.isFocused()&&usable()&&Minecraft.getInstance().isWindowActive();keyArmed=-1;HELD.remove(this);
        visual(Button.State.DEFAULT,false);if(activate)activate(event);
    }
    private void activate(UIEvent event){UISoundUtils.playButtonClickSound();var action=access.divzero$clickHandler();if(action!=null)action.handleEvent(event);}
    private void detachRelease(){if(releaseRoot!=null){releaseRoot.removeEventListener(UIEvents.MOUSE_UP,outsideRelease);releaseRoot=null;}}
    private void cancel(boolean immediate){mouseArmed=false;keyArmed=-1;HELD.remove(this);detachRelease();visual(Button.State.DEFAULT,immediate);}
    private void visual(Button.State state,boolean immediate){
        if(button.getState()==state&&!immediate)return;access.divzero$buttonState(state);
        if(restingText==null){if(state!=Button.State.PRESSED)return;restingText=button.text.getStyle().transform2D().copy();}
        var target=restingText.copy();if(state==Button.State.PRESSED)target.translate(restingText.translate().resolveX(button.text.getSizeWidth())+1,restingText.translate().resolveY(button.text.getSizeHeight())+1);
        if(animation!=null){animation.unsubscribe();animation=null;}
        if(immediate||button.getModularUI()==null||button.getModularUI().isRemoved()){
            button.text.getStyleBag().removeCandidates(PropertyRegistry.TRANSFORM_2D,slot->slot.origin()==StyleOrigin.ANIMATION);
            button.text.getStyle().transform2D(target);
        }else animation=button.text.animation().duration(0.07f).style(PropertyRegistry.TRANSFORM_2D,target).start();
    }
}
