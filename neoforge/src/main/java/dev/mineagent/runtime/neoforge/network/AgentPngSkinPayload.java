package dev.mineagent.runtime.neoforge.network;
import java.util.UUID;import net.minecraft.network.RegistryFriendlyByteBuf;import net.minecraft.network.codec.StreamCodec;import net.minecraft.network.protocol.common.custom.CustomPacketPayload;import net.minecraft.resources.Identifier;
public record AgentPngSkinPayload(UUID agent,String sha256,String model,long revision,byte[] png) implements CustomPacketPayload {
 public AgentPngSkinPayload{png=png.clone();if(!sha256.matches("[a-f0-9]{64}")||!java.util.Set.of("wide","slim").contains(model)||revision<0||png.length>65536)throw new IllegalArgumentException("SKIN_PAYLOAD");}
 @Override public byte[] png(){return png.clone();}
 public static final Type<AgentPngSkinPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","agent_png_skin"));
 public static final StreamCodec<RegistryFriendlyByteBuf,AgentPngSkinPayload> CODEC=CustomPacketPayload.codec((v,b)->{b.writeUUID(v.agent);b.writeUtf(v.sha256,64);b.writeUtf(v.model,4);b.writeVarLong(v.revision);b.writeByteArray(v.png);},b->new AgentPngSkinPayload(b.readUUID(),b.readUtf(64),b.readUtf(4),b.readVarLong(),b.readByteArray(65536)));
 public Type<AgentPngSkinPayload> type(){return TYPE;}
}
