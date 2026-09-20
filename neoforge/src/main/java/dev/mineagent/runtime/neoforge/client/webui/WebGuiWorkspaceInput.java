package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.client.webui.WorkspaceShortcut;
import dev.mineagent.runtime.neoforge.client.MineAgentClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;
/** The Controls binding is intercepted before vanilla screenshot/GUI processing. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WebGuiWorkspaceInput {
    private static final WorkspaceShortcut SHORTCUT=new WorkspaceShortcut();private static Object connection;
    private WebGuiWorkspaceInput(){}
    public static boolean eligible(){var mc=Minecraft.getInstance();return mc.level!=null&&mc.player!=null&&!mc.player.isDeadOrDying()&&mc.getOverlay()==null&&mc.isWindowActive()&&(mc.screen==null||mc.screen instanceof WebGuiInteractionScreen||mc.screen instanceof WebGuiDiagnosticScreen);}
    private static boolean modifiers(int bits){return switch(MineAgentClientMod.TOGGLE_WORKSPACE.getKeyModifier()){
        case NONE->(bits&(GLFW.GLFW_MOD_SHIFT|GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_ALT|GLFW.GLFW_MOD_SUPER))==0;
        case SHIFT->(bits&GLFW.GLFW_MOD_SHIFT)!=0;case CONTROL->(bits&GLFW.GLFW_MOD_CONTROL)!=0;case ALT->(bits&GLFW.GLFW_MOD_ALT)!=0;
        case CONTROL_OR_COMMAND->(bits&(net.minecraft.client.input.InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY?GLFW.GLFW_MOD_SUPER:GLFW.GLFW_MOD_CONTROL))!=0;
    };}
    public static boolean key(long window,int action,KeyEvent event,boolean nativeComposing){
        var mc=Minecraft.getInstance();if(window!=mc.getWindow().handle())return false;
        var binding=MineAgentClientMod.TOGGLE_WORKSPACE;boolean matches=!binding.isUnbound()&&binding.matches(event)&&modifiers(event.modifiers());
        long physical=event.key()==GLFW.GLFW_KEY_UNKNOWN?0x100000000L+(event.scancode()&0xffffffffL):event.key();
        return handle(physical,action,matches,nativeComposing||WebGuiHostAdapter.INSTANCE.compositionActive());
    }
    private static boolean handle(long physical,int action,boolean matches,boolean composing){
        var result=SHORTCUT.event(physical,action,matches,eligible(),composing);
        if(result==WorkspaceShortcut.Result.TOGGLE){resetHeldInput();WebGuiHostAdapter.INSTANCE.toggleWorkspace();resetHeldInput();}
        return result!=WorkspaceShortcut.Result.PASS;
    }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Pre event){
        var binding=MineAgentClientMod.TOGGLE_WORKSPACE;boolean matches=binding.getKey().getType()==com.mojang.blaze3d.platform.InputConstants.Type.MOUSE&&binding.getKey().getValue()==event.getButton()&&modifiers(event.getModifiers());
        if(handle(0x200000000L+event.getButton(),event.getAction(),matches,WebGuiHostAdapter.INSTANCE.compositionActive()))event.setCanceled(true);
    }
    public static void resetHeldInput(){
        var mc=Minecraft.getInstance();WebGuiNativeInput.clear();if(mc.screen instanceof WebGuiInteractionScreen screen)screen.releasePressed();KeyMapping.releaseAll();
        var mouse=(dev.mineagent.runtime.neoforge.mixin.client.WorkspaceMouseAccess)mc.mouseHandler;mouse.mineagent$left(false);mouse.mineagent$middle(false);mouse.mineagent$right(false);mouse.mineagent$activeButton(null);mouse.mineagent$fakeRight(0);mc.mouseHandler.setIgnoreFirstMove();
    }
    public static void tick(){var mc=Minecraft.getInstance();if(connection!=mc.getConnection()||!mc.isWindowActive()||mc.player==null){SHORTCUT.reset();connection=mc.getConnection();}}
}
