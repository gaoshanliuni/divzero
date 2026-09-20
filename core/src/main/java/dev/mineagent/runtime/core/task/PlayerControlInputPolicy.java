package dev.mineagent.runtime.core.task;

/** While the game is owned by a body-control lease, only Esc is a manual exit; other physical input is suppressed. */
public final class PlayerControlInputPolicy {
    public enum Result { PASS,BLOCK,STOP }
    private PlayerControlInputPolicy(){}
    public static Result key(boolean controlling,boolean ownWindow,int key,int action){
        if(!controlling||!ownWindow)return Result.PASS;
        return key==256&&action==1?Result.STOP:Result.BLOCK; // GLFW Escape / press; no dependency on client-only classes.
    }
}
