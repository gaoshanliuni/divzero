package dev.mineagent.runtime.neoforge.client.nativeui;
import net.minecraft.client.Minecraft;
/** The native HUD owns visibility from 26.2 onwards. Do not maintain a second visibility flag. */
public final class NativeTargetApi {
    public static void hideHud(Minecraft client,boolean hide){if(client.gui.hud.isHidden()!=hide)client.gui.hud.toggle();}
    private NativeTargetApi(){}
}
