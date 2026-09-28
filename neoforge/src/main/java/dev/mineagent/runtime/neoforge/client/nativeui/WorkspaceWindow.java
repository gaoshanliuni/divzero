package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.vfyjxf.taffy.style.*;
import java.util.function.Consumer;

/** In-game draggable/resizable window with a dock entry; it never creates a separate OS window. */
public final class WorkspaceWindow {
    public final Dialog dialog;public final UIElement body;private final Button dockButton;private boolean closed;private Placement lastPlacement;
    public WorkspaceWindow(UIElement desktop,UIElement dock,String title,float x,float y,float width,float height,Consumer<WorkspaceWindow> onClose){
        lastPlacement=new Placement(x,y,width,height,false);
        dialog=new Dialog().setAutoClose(false).allowInteraction().windowMode(x,y,width,height).setClickOutsideClose(false);
        dialog.getLayout().left(0).top(0).alignSelf(AlignItems.FLEX_START);
        dialog.overlay.getStyle().backgroundTexture(NativeUiTheme.panel());
        dialog.titleBar.clearAllChildren();dialog.titleBar.getLayout().height(29).paddingHorizontal(9).paddingVertical(4);
        dialog.titleBar.getStyle().backgroundTexture(NativeUiTheme.title());
        var heading=NativeUiTheme.text(title,0xffffffff,10);heading.getLayout().flex(1);dialog.titleBar.addChild(heading);
        dialog.titleBar.addChild(NativeUiTheme.button("—",()->dialog.setDisplay(false)));
        dialog.titleBar.addChild(NativeUiTheme.button("×",this::close));
        body=dialog.contentContainer;body.getStyle().backgroundTexture(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.MCSprites.RECT_THIN.copy().setColor(0xedffffff));body.getLayout().flex(1).paddingAll(9).alignItems(AlignItems.STRETCH).justifyContent(AlignContent.FLEX_START);
        dialog.buttonContainer.setDisplay(false);dialog.show(desktop);body.setDisplay(true);
        dockButton=NativeUiTheme.button(title,()->{dialog.setDisplay(true);dialog.getStyle().zIndex(++zOrder);});dock.addChild(dockButton);
        dialog.setOnClose(()->{closed=true;dockButton.removeSelf();onClose.accept(this);});dialog.getStyle().zIndex(++zOrder);
    }
    private static int zOrder=10;
    public record Placement(float x,float y,float width,float height,boolean minimized){}
    public Placement placement(){var box=dialog.overlay;if(box.getSizeWidth()>0&&box.getSizeHeight()>0)lastPlacement=new Placement(box.getPositionX(),box.getPositionY(),box.getSizeWidth(),box.getSizeHeight(),!dialog.isDisplayed());return new Placement(lastPlacement.x(),lastPlacement.y(),lastPlacement.width(),lastPlacement.height(),!dialog.isDisplayed());}
    public void restore(Placement value){
        var mc=net.minecraft.client.Minecraft.getInstance();float width=mc.getWindow().getGuiScaledWidth(),height=mc.getWindow().getGuiScaledHeight();
        if(value.width()<=0||value.height()<=0||!Float.isFinite(value.x())||!Float.isFinite(value.y())||!Float.isFinite(value.width())||!Float.isFinite(value.height()))return;
        var box=dialog.overlay;box.getLayout().width(Math.clamp(value.width(),220,Math.max(220,width-14))).height(Math.clamp(value.height(),160,Math.max(160,height-82)));
        float x=Math.clamp(value.x(),7,Math.max(7,width-150)),y=Math.clamp(value.y(),48,Math.max(48,height-95));
        var parent=dialog.getParent();if(parent!=null){var local=parent.worldToLocalLayoutOffset(new org.joml.Vector2f(x,y));box.getLayout().left(local.x).top(local.y);}
        dialog.setDisplay(!value.minimized());
    }
    public boolean visible(){return !closed&&dialog.isDisplayed();}
    public boolean closed(){return closed;}
    public void reveal(){if(!closed){dialog.setDisplay(true);dialog.getStyle().zIndex(++zOrder);}}
    public void close(){if(!closed){placement();dialog.close();}}
}
