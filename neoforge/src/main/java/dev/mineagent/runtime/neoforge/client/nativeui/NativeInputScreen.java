package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Common native editor focus/IME lifecycle; preedit never becomes committed widget text. */
public class NativeInputScreen extends ModularUIScreen implements NativeComposition {
    private final NativeImeSupport ime=new NativeImeSupport(this,()->modularUI);
    public NativeInputScreen(ModularUI ui,Component title){super(ui,title);}
    @Override public void tick(){super.tick();ime.update();}
    @Override public boolean preeditUpdated(PreeditEvent event){return ime.preedit(event);}
    @Override public boolean nativeComposing(){return ime.composing();}
    @Override public boolean keyPressed(KeyEvent event){return ime.consume(event)||NativeInventoryPanel.key(this,event)||super.keyPressed(event);}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){return NativeInventoryPanel.click(this,event,twice)||super.mouseClicked(event,twice);}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){return NativeInventoryPanel.drag(this,event,dx,dy)||super.mouseDragged(event,dx,dy);}
    @Override public boolean mouseReleased(MouseButtonEvent event){return NativeInventoryPanel.release(this,event)||super.mouseReleased(event);}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){super.extractRenderState(graphics,mouseX,mouseY,partial);NativeInventoryPanel.tooltip(this,graphics,mouseX,mouseY);ime.render(graphics,mouseX,mouseY,partial);}
    java.util.Map<String,Object> smokeInputState(){if(!Boolean.getBoolean("mineagent.skillSmoke")&&!Boolean.getBoolean("mineagent.nativeUiSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return ime.observation();}
    @Override public void removed(){NativeInventoryPanel.removed(this);ime.release();super.removed();}
}
