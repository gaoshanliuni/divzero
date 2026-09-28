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
    public static String state(String code){return dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(switch(code){case "READY"->"就绪";case "CREATOR"->"创造模式";case "SURVIVAL"->"生存模式";case "NOT_LOADED","UNLOADED"->"身体未加载";case "PLANNED"->"待施工";case "PREPARING"->"准备中";case "APPLYING"->"施工中";case "VERIFIED"->"验证通过";case "UNVERIFIED"->"尚未验证";case "PAUSED"->"已暂停";case "UNDONE"->"已撤销";case "PARTIAL"->"部分完成";case "CONFLICT"->"发生冲突";case "UNKNOWN"->"结果待核对";case "REJECTED"->"已拒绝";case "EMPTY"->"尚无计划";default->code;});}
    private NativeUiTheme(){}
    public static Stylesheet mc(){return StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC);}
    public static UI ui(UIElement root){root.addClass(NativeButtonFeedback.ROOT_CLASS);return UI.of(root,List.of(mc()),size->size);}
    public static IGuiTexture panel(){return MCSprites.RECT.copy().setColor(0xedffffff);}
    public static IGuiTexture inset(){return MCSprites.RECT_5.copy().setColor(0xedffffff);}
    public static IGuiTexture title(){return MCSprites.RECT_BORDER.copy().setColor(0xf5ffffff);}
    public static <T extends UIElement> T card(T element){element.getStyle().backgroundTexture(panel());element.getLayout().paddingAll(9);return element;}
    public static Button button(String text,Runnable click){
        var button=new Button().setText(Component.literal(text));button.getLayout().height(23).paddingHorizontal(8).marginRight(5);
        button.text.textStyle(s->s.fontSize(9));button.setOnClick(event->click.run());return button;
    }
    public static Button iconButton(String glyph,Runnable action){
        var button=button(glyph,action);button.getLayout().width(23).height(23).minWidth(23).maxWidth(23).minHeight(23).maxHeight(23).flexGrow(0).flexShrink(0).paddingAll(0).marginAll(0).alignSelf(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        button.text.getLayout().marginAll(0);button.text.textStyle(style->style.fontSize(10));return button;
    }
    public static TextElement text(String value,int color,float size){var text=new TextElement().setText(Component.literal(value));text.textStyle(s->s.textColor(color).textShadow(false).fontSize(size).textWrap(TextWrap.WRAP).adaptiveWidth(false).adaptiveHeight(true));text.getLayout().widthPercent(100);return text;}
    /** Preserve MC stylesheet textures; only set content-specific typography and empty placeholders. */
    public static void controls(UIElement root){
        if(root instanceof Button button)NativeButtonFeedback.install(button);
        if(!root.hasClass("divzero-control-style")){
            root.addClass("divzero-control-style");
            if(root instanceof TextField field){field.textFieldStyle(s->s.textColor(0xffffffff));if(field.getTextFieldStyle().placeholder().getString().equals("Empty"))field.textFieldStyle(s->s.placeholder(Component.empty()));}
            else if(root instanceof TextArea area)area.textAreaStyle(s->s.textColor(0xffffffff).placeholder(Component.empty()));
            else if(root instanceof ProgressBar progress)progress.bar.getStyle().backgroundTexture(new ColorRectTexture(0xff6d9b31));
        }
        for(var child:root.getChildren())controls(child);
    }
}
