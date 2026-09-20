package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class PlayerBodyLevelMixin {
    @Inject(method="setLevel",at=@At("HEAD"))
    private void mineagent$bodyLevel(ClientLevel next,CallbackInfo ci){dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.contextBoundary("WORLD_CHANGED");}
    @Inject(method="disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",at=@At("HEAD"))
    private void mineagent$bodyDisconnect(net.minecraft.client.gui.screens.Screen screen,boolean transfer,boolean skip,CallbackInfo ci){dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.contextBoundary("DISCONNECTED");}
}
