package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.ui.ContainerOpenScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(ServerPlayer.class)
public abstract class ContainerScreenOpenMixin {
    @Redirect(method="openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"),require=1)
    private void mineagent$screen(ServerGamePacketListenerImpl connection,Packet<?> packet){if(!ContainerOpenScope.suppress((ServerPlayer)(Object)this))connection.send(packet);}
    @Redirect(method="openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"),require=1)
    private void mineagent$advanced(ServerGamePacketListenerImpl connection,CustomPacketPayload packet){if(!ContainerOpenScope.suppress((ServerPlayer)(Object)this))connection.send(packet);}
}
