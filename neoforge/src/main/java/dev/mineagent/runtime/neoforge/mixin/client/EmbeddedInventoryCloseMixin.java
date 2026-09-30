package dev.mineagent.runtime.neoforge.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class EmbeddedInventoryCloseMixin {
    @Inject(method="handleContainerClose",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/LocalPlayer;clientSideCloseContainer()V"),cancellable=true)
    private void divzero$closeEmbeddedMenu(ClientboundContainerClosePacket packet,CallbackInfo callback){
        if(dev.mineagent.runtime.neoforge.client.nativeui.NativeInventoryPanel.serverClosed(packet.getContainerId()))callback.cancel();
    }
}
