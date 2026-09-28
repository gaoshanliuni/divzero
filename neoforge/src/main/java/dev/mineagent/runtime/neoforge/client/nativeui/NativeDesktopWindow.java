package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.util.WindowDragHelper;
import dev.vfyjxf.taffy.style.*;
import org.joml.Vector2f;

/** Reusable window primitive inside an AI-authored desktop, with ordinary child controls. */
final class NativeDesktopWindow extends UIElement {
    final UIElement content=new UIElement();
    private final Button task;private final com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement heading;
    private boolean minimized,maximized;
    private float left,top,width,height;
    NativeDesktopWindow(String title){
        addClass("divzero-desktop-window");
        Style.defaultPipeline(getLayout(),layout->layout.positionType(TaffyPosition.ABSOLUTE).left(24).top(24).width(300).height(210).minWidth(140).minHeight(90));
        Style.defaultPipeline(getStyle(),style->style.backgroundTexture(NativeUiTheme.panel()));
        var bar=new UIElement();bar.addClass("divzero-window-title");bar.getLayout().flexDirection(FlexDirection.ROW).height(29).paddingAll(3).gapAll(3);
        Style.defaultPipeline(bar.getStyle(),style->style.backgroundTexture(NativeUiTheme.title()));
        heading=NativeUiTheme.text(title,0xffffffff,10);heading.getLayout().width(0).flex(1);bar.addChild(heading);
        bar.addChild(NativeUiTheme.iconButton("—",()->{minimized=true;setDisplay(false);}));
        bar.addChild(NativeUiTheme.iconButton("□",this::maximize));
        bar.addChild(NativeUiTheme.iconButton("×",()->{setDisplay(false);taskVisible(false);}));
        addChild(bar);content.getLayout().flex(1).widthPercent(100).paddingAll(6);content.addClass("divzero-window-content");addChild(content);
        WindowDragHelper.setDragMove(bar,this,null,null);
        WindowDragHelper.setBorderResize(this,this,2,new Vector2f(140,90),new Vector2f(Float.MAX_VALUE),event->!maximized,(event,handle)->true,event->{});
        task=NativeUiTheme.button(title,()->{minimized=false;setDisplay(true);getStyle().zIndex(++front);});task.addClass("divzero-window-task");task.getLayout().marginAll(0);
    }
    void title(String value){heading.setText(net.minecraft.network.chat.Component.literal(value));task.setText(net.minecraft.network.chat.Component.literal(value));}
    private static int front=100;
    private void taskVisible(boolean value){if(task!=null)task.setDisplay(value);}
    void dock(UIElement dock){dock.addChild(task);}
    private void maximize(){
        if(!maximized){left=getPositionX();top=getPositionY();width=getSizeWidth();height=getSizeHeight();getLayout().left(0).top(0).widthPercent(100).heightPercent(90);}
        else {var parent=getParent();var point=parent==null?new Vector2f(left,top):parent.worldToLocalLayoutOffset(new Vector2f(left,top));getLayout().left(point.x).top(point.y).width(width).height(height);}
        maximized=!maximized;
    }
    void copyPlacementTo(NativeDesktopWindow next){
        if(getSizeWidth()>0&&getSizeHeight()>0){var parent=getParent();var point=parent==null?new Vector2f(getPositionX(),getPositionY()):parent.worldToLocalLayoutOffset(new Vector2f(getPositionX(),getPositionY()));next.getLayout().left(point.x).top(point.y).width(getSizeWidth()).height(getSizeHeight());}
        next.minimized=minimized;next.maximized=maximized;next.left=left;next.top=top;next.width=width;next.height=height;
        next.setDisplay(isDisplayed());next.taskVisible(task.isDisplayed());
    }
}
