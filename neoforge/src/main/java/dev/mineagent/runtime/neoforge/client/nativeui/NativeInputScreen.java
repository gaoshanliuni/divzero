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
    @Override public boolean keyPressed(KeyEvent event){return ime.consume(event)||super.keyPressed(event);}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){super.extractRenderState(graphics,mouseX,mouseY,partial);ime.render(graphics,mouseX,mouseY,partial);}
    @Override public void removed(){ime.release();super.removed();}
}
