package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.*;
import java.util.function.Supplier;

/** Register the actual focused native editor with Minecraft's OS text input manager. */
final class NativeImeSupport {
    private final Screen screen;private final Supplier<ModularUI> ui;
    private UIElement editor;private IMEPreeditOverlay overlay;private boolean composing,changingFocus;private int x,y,lastX=Integer.MIN_VALUE,lastY,lastHeight;private double lastScale;private long ended;
    NativeImeSupport(Screen screen,Supplier<ModularUI> ui){this.screen=screen;this.ui=ui;}
    void update(){
        if(changingFocus)return;var mc=Minecraft.getInstance();UIElement next=null;
        if(mc.screen==screen&&mc.isWindowActive()&&ui.get()!=null){var focus=ui.get().getFocusedElement();for(var p=focus;p!=null;p=p.getParent()){if(!p.isDisplayed()||!p.isActive()){next=null;break;}if((p instanceof TextField||p instanceof TextArea))next=p;}}
        if(next!=editor){var previous=editor;editor=next;lastX=Integer.MIN_VALUE;overlay=null;composing=false;ended=0;changingFocus=true;try{if(previous!=null)mc.onTextInputFocusChange(screen,false);if(editor!=null)mc.onTextInputFocusChange(screen,true);}finally{changingFocus=false;}}
        if(editor==null)return;float caretX=editor.getContentX(),caretY=editor.getContentY(),line=10;
        if(editor instanceof TextField field){String value=field.getValue();line=field.getTextFieldStyle().fontSize();caretX+=mc.font.width(value.substring(0,Math.clamp(field.getCursorPos(),0,value.length())))*line/9-field.getDisplayOffset();}
        else if(editor instanceof TextArea area){var lines=area.getValue();int row=Math.clamp(area.getCursorLine(),0,Math.max(0,lines.length-1));String value=lines.length==0?"":lines[row];line=area.getTextAreaStyle().fontSize()+area.getTextAreaStyle().lineSpacing();caretX+=mc.font.width(value.substring(0,Math.clamp(area.getCursorCol(),0,value.length())))*area.getTextAreaStyle().fontSize()/9-area.getScrollX();caretY+=row*line-area.getScrollY();}
        x=(int)Math.clamp(caretX,editor.getContentX(),Math.max(editor.getContentX(),editor.getContentX()+editor.getContentWidth()-1));y=(int)Math.clamp(caretY,editor.getContentY(),Math.max(editor.getContentY(),editor.getContentY()+editor.getContentHeight()-line));
        int height=(int)Math.max(10,line);double scale=mc.getWindow().getGuiScale();
        if(x!=lastX||y!=lastY||height!=lastHeight||scale!=lastScale){
            mc.textInputManager().setTextInputArea(x,y,x+1,y+height);
            lastX=x;lastY=y;lastHeight=height;lastScale=scale;
        }
    }
    boolean preedit(PreeditEvent event){
        if(changingFocus)return true;update();if(editor==null)return false;boolean active=event!=null&&!event.fullText().isEmpty();if(!active&&composing)ended=System.nanoTime();composing=active;
        // A secret field's formatter must not be bypassed by an unmasked composition overlay.
        boolean masked=editor instanceof TextField field&&field.getFormatter()!=null;
        overlay=active&&!masked?new IMEPreeditOverlay(event,Minecraft.getInstance().font,10):null;return true;
    }
    boolean composing(){return composing;}
    boolean consume(KeyEvent event){return composing||ended!=0&&System.nanoTime()-ended<150_000_000L&&(event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE||event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);}
    void render(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){update();if(overlay!=null){overlay.updateInputPosition(x,y);overlay.extractRenderState(graphics,mouseX,mouseY,partial);}}
    void release(){var previous=editor;editor=null;overlay=null;composing=false;ended=0;if(previous!=null&&!changingFocus){changingFocus=true;try{Minecraft.getInstance().onTextInputFocusChange(screen,false);}finally{changingFocus=false;}}}
}
