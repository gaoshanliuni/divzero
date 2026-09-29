package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Interactive LDLib2 HUD; its own buttons alone consume clicks, without opening a blocking Screen. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class AutonomyControlPanel {
    private static ModularUI ui;private static UIElement panel;private static TextElement title,goal,summary;private static Button pause,exit,append;private static boolean pressed;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static String t(String value){return ClientLanguage.t(value);}
    private static void create(){
        var canvas=new UIElement();canvas.getLayout().widthPercent(100).heightPercent(100);canvas.addClass(NativeButtonFeedback.ROOT_CLASS);
        panel=NativeUiTheme.card(new UIElement());panel.getLayout().positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE).left(8).top(48).width(246).gapAll(5);
        title=NativeUiTheme.text("",NativeUiTheme.ACCENT,10);goal=NativeUiTheme.text("",NativeUiTheme.TEXT,9);summary=NativeUiTheme.text("",NativeUiTheme.MUTED,9);
        panel.addChild(title);panel.addChild(goal);panel.addChild(summary);
        var buttons=new UIElement();buttons.getLayout().flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).widthPercent(100).gapAll(3);
        pause=NativeUiTheme.button(t("暂停"),AutonomousBodyClient::togglePause);exit=NativeUiTheme.button(t("退出"),()->AutonomousBodyClient.stop("USER_EXIT_BUTTON"));append=NativeUiTheme.button(t("追加命令"),AutonomousBodyClient::appendCommand);
        for(var button:java.util.List.of(pause,exit,append)){button.getLayout().flexGrow(1).flexShrink(1).width(0).minWidth(0).marginAll(0).paddingHorizontal(3);buttons.addChild(button);}
        panel.addChild(buttons);panel.addChild(NativeUiTheme.text(t("ESC 退出 · T / F2 可继续交流"),NativeUiTheme.MUTED,8));canvas.addChild(panel);ui=new ModularUI(NativeUiTheme.ui(canvas),mc().player);ui.setTickWhileRending(true);ui.init(mc().getWindow().getGuiScaledWidth(),mc().getWindow().getGuiScaledHeight());NativeUiTheme.controls(canvas);
    }
    public static void clear(){pressed=false;if(ui!=null&&!ui.isRemoved())ui.onRemoved();ui=null;panel=null;}
    public static void render(GuiGraphicsExtractor graphics){if(!AutonomousBodyClient.active()||mc().player==null||mc().options.hideGui){clear();return;}if(ui==null)create();int w=mc().getWindow().getGuiScaledWidth(),h=mc().getWindow().getGuiScaledHeight();if(ui.getScreenWidth()!=w||ui.getScreenHeight()!=h)ui.init(w,h);panel.getLayout().width(Math.min(246,w-16));
        title.setText(Component.literal(t(AutonomousBodyClient.manuallyPaused()?"托管已暂停":"正在托管")+" · "+AutonomousBodyClient.panelAgent()));String text=AutonomousBodyClient.panelGoal();if(text.codePointCount(0,text.length())>64)text=text.substring(0,text.offsetByCodePoints(0,64))+"…";goal.setText(Component.literal(t(text)));summary.setText(Component.literal(t(AutonomousBodyClient.panelSummary())));pause.setText(Component.literal(t(AutonomousBodyClient.manuallyPaused()?"继续":"暂停")));
        var mouse=mc().mouseHandler;ModularUIClientAccess.getWidget(ui).extractRenderState(graphics,(int)mouse.getScaledXPos(mc().getWindow()),(int)mouse.getScaledYPos(mc().getWindow()),0);
    }
    @SubscribeEvent public static void screen(ScreenEvent.Render.Post event){if(AutonomousBodyClient.active())render(event.getGuiGraphics());}
    public static boolean mouse(InputEvent.MouseButton.Pre event){if(ui==null||panel==null||!AutonomousBodyClient.active()||!mc().isWindowActive()||event.getButton()!=0)return false;double x=mc().mouseHandler.getScaledXPos(mc().getWindow()),y=mc().mouseHandler.getScaledYPos(mc().getWindow());boolean hit=panel.isMouseOver((float)x,(float)y);if(!hit&&!pressed)return false;ui.refreshHoveredElementAtScreen((float)x,(float)y);var widget=ModularUIClientAccess.getWidget(ui);var input=new MouseButtonEvent(x,y,event.getMouseButtonInfo());if(event.getAction()==1){pressed=true;widget.mouseClicked(input,false);}else if(event.getAction()==0){pressed=false;widget.mouseReleased(input);}return true;}
    public static java.util.Map<String,Object> observation(){return java.util.Map.of("visible",ui!=null,"paused",AutonomousBodyClient.manuallyPaused(),"cursorFree",!mc().mouseHandler.isMouseGrabbed(),"width",panel==null?0:panel.getSizeWidth(),"height",panel==null?0:panel.getSizeHeight());}
    public static void smokeClick(String name){if(!Boolean.getBoolean("mineagent.skillSmoke")||ui==null)throw new IllegalStateException("AUTONOMY_PANEL_SMOKE_UNAVAILABLE");var button=switch(name){case "pause"->pause;case "exit"->exit;case "append"->append;default->throw new IllegalArgumentException();};float x=button.getPositionX()+button.getSizeWidth()/2,y=button.getPositionY()+button.getSizeHeight()/2;ui.refreshHoveredElementAtScreen(x,y);var input=new MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(0,0));var widget=ModularUIClientAccess.getWidget(ui);widget.mouseClicked(input,false);widget.mouseReleased(input);}
    private AutonomyControlPanel(){}
}
