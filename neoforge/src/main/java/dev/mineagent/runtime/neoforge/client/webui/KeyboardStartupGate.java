package dev.mineagent.runtime.neoforge.client.webui;

/** GLFW can deliver queued keys while Minecraft is still inside its constructor. */
public final class KeyboardStartupGate {
    private static int suppressed;
    private KeyboardStartupGate(){}
    public static boolean discard(Object framerateTracker){if(framerateTracker!=null)return false;if(Boolean.getBoolean("mineagent.bootstrapKeySmoke"))suppressed++;return true;}
    public static void probe(net.minecraft.client.KeyboardHandler handler,long window){
        if(!Boolean.getBoolean("mineagent.bootstrapKeySmoke"))return;
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.getFramerateLimitTracker()!=null)throw new IllegalStateException("BOOTSTRAP_KEY_PROBE_TOO_LATE");
        int before=suppressed;var access=(dev.mineagent.runtime.neoforge.mixin.client.WorkspaceKeyboardAccess)handler;
        for(int key:new int[]{org.lwjgl.glfw.GLFW.GLFW_KEY_F2,org.lwjgl.glfw.GLFW.GLFW_KEY_A})for(int action:new int[]{1,2,0})access.mineagent$keyPress(window,action,new net.minecraft.client.input.KeyEvent(key,0,0));
        if(suppressed-before!=6)throw new IllegalStateException("BOOTSTRAP_KEY_PROBE_NOT_GUARDED");
        System.out.println("MINEAGENT_BOOTSTRAP_KEY_GUARD_OK nativeCallbacks=6 systemInputInjected=false");
    }
}
