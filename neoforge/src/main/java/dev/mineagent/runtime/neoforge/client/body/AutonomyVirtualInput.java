package dev.mineagent.runtime.neoforge.client.body;
import net.minecraft.client.*;import net.minecraft.world.entity.player.Input;
/** Per-player game input, independent of desktop focus, GUI typing and the physical mouse cursor. */
public final class AutonomyVirtualInput {
    private static boolean forward,back,left,right,jump,shift,sprint;
    public static void clear(){forward=back=left=right=jump=shift=sprint=false;}
    public static void key(KeyMapping key,boolean down){var o=Minecraft.getInstance().options;if(key==o.keyUp)forward=down;else if(key==o.keyDown)back=down;else if(key==o.keyLeft)left=down;else if(key==o.keyRight)right=down;else if(key==o.keyJump)jump=down;else if(key==o.keyShift)shift=down;else if(key==o.keySprint)sprint=down;}
    public static Input snapshot(){return new Input(forward,back,left,right,jump,shift,sprint);}
    private AutonomyVirtualInput(){}
}
