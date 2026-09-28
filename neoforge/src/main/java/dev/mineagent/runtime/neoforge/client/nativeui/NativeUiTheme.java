package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.style.*;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.MCSprites;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Minecraft is the explicit default theme for built-ins and generated UI. */
public final class NativeUiTheme {
    public static final int TEXT=0xff262626,MUTED=0xff4a4a4a,ACCENT=0xff264b20;
    private NativeUiTheme(){}
    public static Stylesheet mc(){return StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC);}
    public static UI ui(UIElement root){return UI.of(root,List.of(mc()),size->size);}
    public static IGuiTexture panel(){return MCSprites.RECT.copy().setColor(0xedffffff);}
    public static IGuiTexture inset(){return MCSprites.RECT_5.copy().setColor(0xedffffff);}
    public static IGuiTexture title(){return MCSprites.RECT_BORDER.copy().setColor(0xf5ffffff);}
    public static <T extends UIElement> T card(T element){element.getStyle().backgroundTexture(panel());element.getLayout().paddingAll(9);return element;}
    public static Button button(String text,Runnable click){
        var button=new Button().setText(Component.literal(text));button.getLayout().height(23).paddingHorizontal(8).marginRight(5);
        button.text.textStyle(s->s.textColor(0xffffffff).fontSize(9));button.setOnClick(event->click.run());return button;
    }
    public static TextElement text(String value,int color,float size){var text=new TextElement().setText(Component.literal(value));text.textStyle(s->s.textColor(color).textShadow(false).fontSize(size).textWrap(TextWrap.WRAP).adaptiveWidth(false).adaptiveHeight(true));text.getLayout().widthPercent(100);return text;}
    /** Preserve MC stylesheet textures; only set content-specific typography and empty placeholders. */
    public static void controls(UIElement root){
        if(!root.hasClass("divzero-control-style")){
            root.addClass("divzero-control-style");
            if(root instanceof TextField field){field.textFieldStyle(s->s.textColor(0xffffffff));if(field.getTextFieldStyle().placeholder().getString().equals("Empty"))field.textFieldStyle(s->s.placeholder(Component.empty()));}
            else if(root instanceof TextArea area)area.textAreaStyle(s->s.textColor(0xffffffff).placeholder(Component.empty()));
            else if(root instanceof ProgressBar progress)progress.bar.getStyle().backgroundTexture(new ColorRectTexture(0xff6d9b31));
        }
        for(var child:root.getChildren())controls(child);
    }
}
