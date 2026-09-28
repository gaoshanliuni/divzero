package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;

/** Isolated real LDLib2 input-dispatch checks; no model requests or world actions. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeButtonSmokeClient {
    private record Step(int delay,String name,Runnable action){}
    private static final ArrayDeque<Step> steps=new ArrayDeque<>();
    private static final List<String> passed=new ArrayList<>();
    private static int ticks,elapsed,clicks,closed;private static boolean started,done;private static String current="startup";
    private static TestScreen screen;private static WorkspaceWindow window;private static Button test,minimize,close;
    private static Path output()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("native-button-smoke"));}
    private static final class TestScreen extends NativeInputScreen {
        final UIElement root;
        TestScreen(UIElement root,boolean owned){super(new ModularUI(owned?NativeUiTheme.ui(root):UI.of(root,List.of(NativeUiTheme.mc()),size->size),Minecraft.getInstance().player),Component.literal("DivZero button test"));this.root=root;root.getLayout().widthPercent(100).heightPercent(100);}
        @Override public void tick(){super.tick();NativeUiTheme.controls(root);}
    }
    private static void require(boolean yes,String error){if(!yes)throw new IllegalStateException(error);}
    private static void later(int delay,String name,Runnable action){steps.addLast(new Step(delay,name,action));}
    private static void move(Button button){move(button.getPositionX()+button.getSizeWidth()/2,button.getPositionY()+button.getSizeHeight()/2);}
    private static void move(double x,double y){var mc=Minecraft.getInstance();double scale=mc.getWindow().getGuiScale();GLFW.glfwSetCursorPos(mc.getWindow().handle(),x*scale,y*scale);mc.screen.mouseMoved(x,y);}
    private static MouseButtonEvent mouse(Button button,int key){return new MouseButtonEvent(button.getPositionX()+button.getSizeWidth()/2,button.getPositionY()+button.getSizeHeight()/2,new MouseButtonInfo(key,0));}
    private static void press(Button button,int key){require(Minecraft.getInstance().isWindowActive(),"TEST_WINDOW_NOT_FOCUSED");screen.mouseClicked(mouse(button,key),false);}
    private static void release(Button button,int key){screen.mouseReleased(mouse(button,key));}
    private static void screenshot(String name){try{net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(output().resolve(name+".png"));}catch(Exception error){fail(error);}});}catch(Exception error){fail(error);}}
    private static void setup(){
        var root=new UIElement();screen=new TestScreen(root,true);var desktop=new UIElement();desktop.getLayout().widthPercent(100).flex(1);root.addChild(desktop);var dock=new UIElement();dock.getLayout().height(25).widthPercent(100);root.addChild(dock);
        window=new WorkspaceWindow(desktop,dock,"DivZero · 按钮交互",30,45,360,230,w->closed++);
        window.body.addChild(NativeUiTheme.text("按下有反馈，松开才执行；拖出后松开取消。",NativeUiTheme.TEXT,10));
        // Deliberately construct the library button directly, as built-in dialogs do.
        test=new Button().setText("测试按钮").setOnClick(event->clicks++);test.getLayout().width(160).height(27);window.body.addChild(test);
        var icons=window.dialog.titleBar.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();minimize=icons.get(0);close=icons.get(1);
        Minecraft.getInstance().setScreen(screen);GLFW.glfwFocusWindow(Minecraft.getInstance().getWindow().handle());
        later(20,"square-title-controls",()->{for(var b:List.of(minimize,close)){require(Math.abs(b.getSizeWidth()-b.getSizeHeight())<0.01f,"TITLE_NOT_SQUARE");require(Math.abs(b.getSizeWidth()-23)<0.01f,"TITLE_SIZE");}move(test);});
        later(5,"mouse-press-does-not-activate",()->{screenshot("01-hover");press(test,0);require(clicks==0&&test.getState()==Button.State.PRESSED,"PRESS_ACTIVATED");});
        later(5,"pressed-animation",()->{require(clicks==0&&test.getState()==Button.State.PRESSED,"HOLD_ACTIVATED");require(test.text.getStyle().transform2D().translate().resolveY(test.text.getSizeHeight())>.8f,"PRESS_ANIMATION_MISSING");screenshot("02-pressed");release(test,0);require(clicks==1,"RELEASE_DID_NOT_ACTIVATE_ONCE");release(test,0);require(clicks==1,"DUPLICATE_RELEASE_ACTIVATED");});
        later(5,"released-animation",()->{require(Math.abs(test.text.getStyle().transform2D().translate().resolveY(test.text.getSizeHeight()))<.1f,"PRESS_OFFSET_STUCK");screenshot("03-released");press(test,0);move(8,8);});
        later(5,"release-outside-cancels",()->{screen.mouseReleased(new MouseButtonEvent(8,8,new MouseButtonInfo(0,0)));require(clicks==1,"OUTSIDE_RELEASE_ACTIVATED");move(test);});
        later(5,"return-after-outside-release-does-not-activate",()->{release(test,0);require(clicks==1,"STALE_PRESS_ACTIVATED");press(test,0);move(8,8);});
        later(5,"drag-back-inside",()->move(test));
        later(5,"release-after-drag-back",()->{release(test,0);require(clicks==2,"DRAG_BACK_RELEASE");press(test,1);release(test,1);require(clicks==2,"RIGHT_CLICK_ACTIVATED");require(!screen.getModularUI().getDragHandler().isDragging(),"BUTTON_STARTED_WINDOW_DRAG");press(test,0);test.setActive(false);release(test,0);require(clicks==2,"DISABLED_RELEASE_ACTIVATED");test.setActive(true);screen.getModularUI().requestFocus(test);});
        later(4,"keyboard-hold",()->{for(int i=0;i<4;i++)screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_SPACE,0,0));require(clicks==2,"KEY_REPEAT_ACTIVATED");require(test.getState()==Button.State.PRESSED,"KEY_PRESS_FEEDBACK");});
        later(4,"keyboard-release",()->{screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_SPACE,0,0));require(clicks==3,"KEY_RELEASE");screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_SPACE,0,0));require(clicks==3,"KEY_RELEASE_DUPLICATED");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0));screen.getModularUI().requestFocus(null);screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0));require(clicks==3,"FOCUS_LOSS_ACTIVATED");move(minimize);});
        later(5,"minimize-press",()->{press(minimize,0);require(window.visible()&&minimize.getState()==Button.State.PRESSED,"MINIMIZED_ON_PRESS");});
        later(5,"minimize-release",()->{screenshot("04-title-pressed");require(window.visible()&&!screen.getModularUI().getDragHandler().isDragging(),"TITLE_HOLD");release(minimize,0);});
        // LDLib2 resolves display layout on the next native frame, not inside the event callback.
        later(3,"minimize-layout",()->{require(!window.visible(),"MINIMIZE_RELEASE");window.reveal();});
        later(3,"restore-layout",()->{require(window.visible(),"MINIMIZE_RESTORE");move(close);});
        later(5,"close-press",()->{press(close,0);require(!window.closed()&&closed==0,"CLOSED_ON_PRESS");});
        later(4,"close-release",()->{release(close,0);require(window.closed()&&closed==1,"CLOSE_RELEASE");});
        later(3,"other-mod-policy",()->{var other=new UIElement();screen=new TestScreen(other,false);test=new Button().setText("Unowned button").setOnClick(event->clicks++);test.getLayout().width(180).height(30);other.addChild(test);Minecraft.getInstance().setScreen(screen);});
        later(5,"unowned-hover",()->move(test));
        later(5,"unowned-button-unchanged",()->{press(test,0);require(clicks==4,"OTHER_MOD_BUTTON_POLICY_CHANGED");release(test,0);});
        // Dialog has its own KEY_DOWN handler; isolate button fallthrough from that library policy.
        later(3,"owned-flat-screen",()->{var root=new UIElement();screen=new TestScreen(root,true);test=new Button().setText("Keyboard fallthrough");root.addChild(test);Minecraft.getInstance().setScreen(screen);});
        later(5,"unrelated-key-fallthrough",()->{screen.getModularUI().requestFocus(test);require(!screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A,0,0)),"UNRELATED_KEY_DOWN_SWALLOWED");require(!screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_A,0,0)),"UNRELATED_KEY_UP_SWALLOWED");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE,0,0));require(Minecraft.getInstance().screen!=screen,"ESCAPE_CLOSE_SWALLOWED");});
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeButtonSmoke")||done)return;
        try{if(++ticks>1800)throw new IllegalStateException("BUTTON_TEST_TIMEOUT");var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;if(!started){started=true;setup();return;}
            if(steps.isEmpty()){Files.writeString(output().resolve("result.json"),new com.google.gson.Gson().toJson(Map.of("status","PASS","modelCalls",0,"checks",passed,"titleSize",23,"activationCount",clicks)));done=true;mc.stop();return;}
            var step=steps.getFirst();if(++elapsed<step.delay)return;elapsed=0;steps.removeFirst();current=step.name;step.action.run();passed.add(current);
        }catch(Exception error){fail(error);}
    }
    private static void fail(Throwable error){if(done)return;done=true;try{Files.writeString(output().resolve("failure.json"),new com.google.gson.Gson().toJson(Map.of("stage",current,"error",error.toString(),"checks",passed,"clicks",clicks)));}catch(Exception ignored){}Minecraft.getInstance().stop();}
    private NativeButtonSmokeClient(){}
}
