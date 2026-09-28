package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import net.minecraft.network.chat.Component;

/** Shared native workspace palette: translucent slate, clear typography, restrained mint accent. */
public final class NativeUiTheme {
    public static final int SURFACE=0xe6192433,RAISED=0xed223147,TEXT=0xffedf4fb,MUTED=0xffa6b8cc,ACCENT=0xff74e0c3,BORDER=0x66526782;
    private NativeUiTheme(){}
    public static SDFRectTexture surface(int color,float radius){return SDFRectTexture.of(color).setRadius(radius).setStroke(1).setBorderColor(BORDER);}
    public static <T extends UIElement> T card(T element){element.getStyle().backgroundTexture(surface(SURFACE,7));element.getLayout().paddingAll(9);return element;}
    public static Button button(String text,Runnable click){
        var button=new Button().setText(Component.literal(text));button.getLayout().height(23).paddingHorizontal(8).marginRight(5);
        button.buttonStyle(s->s.baseTexture(surface(0xab26364c,5)).hoverTexture(surface(0xf137506a,5)).pressedTexture(surface(0xff245b60,5)));
        button.text.textStyle(s->s.textColor(TEXT).fontSize(9));button.setOnClick(event->click.run());return button;
    }
    public static TextElement text(String value,int color,float size){var text=new TextElement().setText(Component.literal(value));text.textStyle(s->s.textColor(color).fontSize(size).textWrap(TextWrap.WRAP).adaptiveWidth(false).adaptiveHeight(true));text.getLayout().widthPercent(100);return text;}
    /** Apply once per built-in control; generated UI continues to own its own styles. */
    public static void controls(UIElement root){
        if(!root.hasClass("divzero-control-style")){
            root.addClass("divzero-control-style");
            if(root instanceof TextField field){field.getStyle().backgroundTexture(surface(0xd3121d2b,4));field.textFieldStyle(s->s.textColor(TEXT));if(field.getTextFieldStyle().placeholder().getString().equals("Empty"))field.textFieldStyle(s->s.placeholder(Component.empty()));}
            else if(root instanceof TextArea area){area.contentView.getStyle().backgroundTexture(surface(0xd3121d2b,5));area.textAreaStyle(s->s.textColor(TEXT).placeholder(Component.empty()));}
            else if(root instanceof Selector<?> selector){selector.getStyle().backgroundTexture(surface(RAISED,4));selector.dialog.getStyle().backgroundTexture(surface(0xfa182638,5));}
            else if(root instanceof ScrollerView scroller)scroller.viewPort.getStyle().backgroundTexture(surface(0x44101a28,4));
            else if(root instanceof ProgressBar progress){progress.barBackground.getStyle().backgroundTexture(surface(0xff101c2a,4));progress.bar.getStyle().backgroundTexture(SDFRectTexture.of(ACCENT).setRadius(4));}
            else if(root instanceof Scroller scroller){scroller.scrollContainer.getStyle().backgroundTexture(SDFRectTexture.of(0x33101927).setRadius(3));scroller.headButton.setDisplay(false);scroller.tailButton.setDisplay(false);scroller.scrollBar.buttonStyle(s->s.baseTexture(SDFRectTexture.of(0xaa647a91).setRadius(3)).hoverTexture(SDFRectTexture.of(ACCENT).setRadius(3)));}
        }
        for(var child:root.getChildren())controls(child);
    }
}
