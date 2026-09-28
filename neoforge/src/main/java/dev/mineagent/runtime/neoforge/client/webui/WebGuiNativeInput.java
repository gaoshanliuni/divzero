package dev.mineagent.runtime.neoforge.client.webui;
import net.minecraft.client.Minecraft;
import java.util.ArrayDeque;

/** Deliver AI actions after the tick's native human-input handlers can revoke delegated control. */
public final class WebGuiNativeInput {
    private static final ArrayDeque<Runnable> pending=new ArrayDeque<>();private static Object connection;
    public static boolean afterInputs(Runnable action){if(!Minecraft.getInstance().isSameThread()||pending.size()>=128)return false;pending.addLast(action);return true;}
    public static void tick(){var current=Minecraft.getInstance().getConnection();if(connection!=current){pending.clear();connection=current;return;}int count=pending.size();for(int i=0;i<count;i++){var action=pending.pollFirst();if(action!=null)action.run();}}
    public static void clear(){pending.clear();}
    public static void cancelNative(){pending.clear();}
    private WebGuiNativeInput(){}
}
