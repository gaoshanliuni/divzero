package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.*;
import dev.mineagent.runtime.neoforge.client.screen.*;
import dev.mineagent.runtime.neoforge.mixin.client.WorkspaceKeyboardAccess;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;

/** Real OS clipboard and real screen routing. Never uses saved credentials or sends the draft to a provider. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ClipboardSmokeClient {
    private record Step(String name,Runnable action){}
    private static final Deque<Step> STEPS=new ArrayDeque<>();private static final List<String> PASSED=new ArrayList<>();
    private static final String DUMMY="clipboard-fixture-not-a-key-中文-"+UUID.randomUUID();
    private static String previous,lastOwned,current="startup";private static int ticks,next;private static boolean started,done;
    private static TextField field;private static EditBox controller;private static LdPanelScreen panel;private static TextArea composer;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void require(boolean yes,String message){if(!yes)throw new IllegalStateException(message);}
    private static void step(String name,Runnable action){STEPS.addLast(new Step(name,action));}
    private static void writeClipboard(String value){mc().keyboardHandler.setClipboard(value);lastOwned=value;}
    private static void key(int code,int mods){var keyboard=(WorkspaceKeyboardAccess)mc().keyboardHandler;var event=new KeyEvent(code,0,mods);keyboard.mineagent$keyPress(mc().getWindow().handle(),GLFW.GLFW_PRESS,event);keyboard.mineagent$keyPress(mc().getWindow().handle(),GLFW.GLFW_RELEASE,event);}
    private static void ctrl(int code){key(code,GLFW.GLFW_MOD_CONTROL);}
    private static UIElement find(UIElement node,Class<?> type){if(type.isInstance(node))return node;for(var child:node.getChildren()){var found=find(child,type);if(found!=null)return found;}return null;}
    private static void click(UIElement element,ModularUI ui){
        float x=element.getPositionX()+element.getSizeWidth()/2,y=element.getPositionY()+element.getSizeHeight()/2;require(element.getSizeWidth()>0&&element.getSizeHeight()>0,"EDITOR_NOT_LAID_OUT");ui.refreshHoveredElementAtScreen(x,y);
        var event=new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));mc().screen.mouseClicked(event,false);mc().screen.mouseReleased(event);
    }
    private static void secret(String key){
        step("open-"+key,()->mc().setScreen(new NativeSecretScreen(null,key)));
        step("focus-"+key,()->{panel=(LdPanelScreen)mc().screen;var ui=panel.clipboardFixtureUi();field=(TextField)find(ui.ui.rootElement,TextField.class);require(field!=null,"SECRET_EDITOR_MISSING");click(field,ui);controller=(EditBox)panel.controllerFocus();require(controller!=null&&field.getFormatter()!=null,"SECRET_FOCUS_OR_MASK_MISSING");com.lowdragmc.lowdraglib2.editor.ClipboardManager.INSTANCE.clear();writeClipboard(DUMMY);});
        step("paste-"+key,()->{require(DUMMY.equals(mc().keyboardHandler.getClipboard()),"SYSTEM_CLIPBOARD_WRITE_NOT_OBSERVED");ctrl(GLFW.GLFW_KEY_A);ctrl(GLFW.GLFW_KEY_V);require(field.getValue().equals(DUMMY)&&controller.getValue().equals(DUMMY),"SECRET_PASTE_OR_CONTROLLER_SYNC_FAILED");require(field.getFormatter().apply(DUMMY).getString().equals("•".repeat(DUMMY.length())),"SECRET_MASK_FAILED");});
        step("copy-"+key,()->{ctrl(GLFW.GLFW_KEY_A);ctrl(GLFW.GLFW_KEY_C);lastOwned=DUMMY;require(mc().keyboardHandler.getClipboard().equals(DUMMY),"SECRET_COPY_FAILED");require(com.lowdragmc.lowdraglib2.editor.ClipboardManager.INSTANCE.getClipboardContent()==null,"SECRET_ENTERED_OBJECT_CLIPBOARD_CACHE");});
        step("cut-"+key,()->{ctrl(GLFW.GLFW_KEY_X);require(field.getValue().isEmpty()&&controller.getValue().isEmpty()&&mc().keyboardHandler.getClipboard().equals(DUMMY),"SECRET_CUT_FAILED");});
        step("shift-insert-"+key,()->{key(GLFW.GLFW_KEY_INSERT,GLFW.GLFW_MOD_SHIFT);require(field.getValue().equals(DUMMY)&&controller.getValue().equals(DUMMY),"SECRET_SHIFT_INSERT_FAILED");});
        step("close-clears-"+key,()->{mc().screen.onClose();require(field.getValue().isEmpty()&&controller.getValue().isEmpty(),"SECRET_RETAINED_AFTER_CLOSE");});
    }
    private static void prepare(){
        previous=mc().keyboardHandler.getClipboard();
        secret("provider.openai.apiKey");secret("provider.asr.apiKey");
        step("open-model-inputs",()->mc().setScreen(new ProviderModelScreen(null)));
        step("model-field-paste-copy",()->{panel=(LdPanelScreen)mc().screen;var ui=panel.clipboardFixtureUi();field=(TextField)find(ui.ui.rootElement,TextField.class);require(field!=null,"MODEL_EDITOR_MISSING");click(field,ui);writeClipboard("fixture-model-id");ctrl(GLFW.GLFW_KEY_A);ctrl(GLFW.GLFW_KEY_V);require(field.getValue().equals("fixture-model-id"),"MODEL_PASTE_FAILED");ctrl(GLFW.GLFW_KEY_A);ctrl(GLFW.GLFW_KEY_INSERT);lastOwned="fixture-model-id";require(mc().keyboardHandler.getClipboard().equals(lastOwned),"MODEL_CTRL_INSERT_FAILED");});
        step("open-F2",()->NativeWorkspaceScreen.open());
        step("F2-os-paste",()->{var screen=(NativeWorkspaceScreen)mc().screen;composer=(TextArea)find(screen.modularUI.ui.rootElement,TextArea.class);require(composer!=null,"F2_COMPOSER_MISSING");click(composer,screen.modularUI);writeClipboard("剪贴板测试\r\n第二行");ctrl(GLFW.GLFW_KEY_A);ctrl(GLFW.GLFW_KEY_V);require(String.join("\n",composer.getValue()).equals("剪贴板测试\n第二行"),"F2_PASTE_FAILED");});
        step("F2-os-copy-cut",()->{ctrl(GLFW.GLFW_KEY_A);ctrl(GLFW.GLFW_KEY_C);lastOwned="剪贴板测试\n第二行";require(mc().keyboardHandler.getClipboard().equals(lastOwned),"F2_COPY_FAILED");ctrl(GLFW.GLFW_KEY_X);require(String.join("\n",composer.getValue()).isEmpty(),"F2_CUT_FAILED");});
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.clipboardSmoke")||done||mc().player==null||mc().getSingleplayerServer()==null)return;ticks++;
        try{
            if(ticks>900)throw new IllegalStateException("CLIPBOARD_TIMEOUT_"+current);
            if(!started){if(ticks<100)return;started=true;GLFW.glfwFocusWindow(mc().getWindow().handle());prepare();next=ticks+12;}
            if(ticks<next||!mc().isWindowActive())return;
            if(STEPS.isEmpty()){finish(null);return;}var action=STEPS.removeFirst();current=action.name;action.action.run();PASSED.add(current);next=ticks+6;
        }catch(Throwable failure){finish(failure);}
    }
    private static void finish(Throwable failure){
        done=true;try{if(panel!=null&&mc().screen==panel)panel.onClose();if(previous!=null&&Objects.equals(mc().keyboardHandler.getClipboard(),lastOwned))mc().keyboardHandler.setClipboard(previous);previous=lastOwned=null;
            var result=new LinkedHashMap<String,Object>();result.put("status",failure==null?"PASS":"FAILED");result.put("stage",current);result.put("checks",PASSED);result.put("systemClipboardVerified",failure==null);result.put("providerRequests",0);if(failure!=null)result.put("error",failure.getClass().getSimpleName()+": "+failure.getMessage());
            Files.writeString(mc().gameDirectory.toPath().resolve("clipboard-smoke.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
        }catch(Exception e){throw new IllegalStateException("CLIPBOARD_FIXTURE_OUTPUT_FAILED",e);}finally{mc().stop();}
    }
    private ClipboardSmokeClient(){}
}
