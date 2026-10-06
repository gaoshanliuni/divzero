package dev.mineagent.runtime.neoforge.client.nativeui;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
/** SDL input uses native scan codes and one-based mouse buttons. Never call GLFW on an SDL window. */
public final class NativePlatformInput {
    private static void current(long window){if(window!=Minecraft.getInstance().getWindow().handle())throw new IllegalArgumentException("NOT_GAME_WINDOW");}
    public static int key(long window,int key){current(window);return key>0&&InputConstants.isKeyDown(key)?InputConstants.PRESS:InputConstants.RELEASE;}
    public static int mouse(long window,int button){current(window);if(button<1||button>8)return InputConstants.RELEASE;int bits=SDLMouse.SDL_GetMouseState((java.nio.FloatBuffer)null,(java.nio.FloatBuffer)null);return (bits&(1<<(button-1)))!=0?InputConstants.PRESS:InputConstants.RELEASE;}
    public static int keycode(int scanCode){return SDLKeyboard.SDL_GetKeyFromScancode(scanCode,(short)0,false);}
    public static void focus(long window){current(window);if(!SDLVideo.SDL_RaiseWindow(window))throw new IllegalStateException("SDL_FOCUS_FAILED");}
    public static void minimize(long window){current(window);if(!SDLVideo.SDL_MinimizeWindow(window))throw new IllegalStateException("SDL_MINIMIZE_FAILED");}
    public static void restore(long window){current(window);if(!SDLVideo.SDL_RestoreWindow(window))throw new IllegalStateException("SDL_RESTORE_FAILED");}
    public static void cursor(long window,double x,double y){current(window);SDLMouse.SDL_WarpMouseInWindow(window,(float)x,(float)y);}
    private NativePlatformInput(){}
}
