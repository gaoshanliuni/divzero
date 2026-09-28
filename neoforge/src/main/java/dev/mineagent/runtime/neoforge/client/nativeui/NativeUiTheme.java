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
}
