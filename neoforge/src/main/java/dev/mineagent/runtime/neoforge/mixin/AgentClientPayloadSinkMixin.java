package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.network.Connection;import net.minecraft.network.protocol.Packet;import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;import net.minecraft.server.network.ServerCommonPacketListenerImpl;import io.netty.channel.ChannelFutureListener;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** The local AI has no client/channel negotiation. Match its existing packet sink before Mod payload validation. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class AgentClientPayloadSinkMixin {
 @Shadow @Final protected Connection connection;
 @Inject(method="send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",at=@At("HEAD"),cancellable=true)
 private void mineagent$localPayload(Packet<?> packet,ChannelFutureListener listener,CallbackInfo ci){
  if(connection instanceof dev.mineagent.runtime.neoforge.body.MineAgentConnection&&packet instanceof ClientboundCustomPayloadPacket){
   // Never affects a real observer's connection, player handshake, or ordinary terminal packet lifecycle.
   ci.cancel();
  }
 }
}
