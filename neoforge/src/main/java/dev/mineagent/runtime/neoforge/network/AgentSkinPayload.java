package dev.mineagent.runtime.neoforge.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;
public record AgentSkinPayload(Map<UUID,Selection> skins) implements CustomPacketPayload {
    public record Selection(String skin,UUID player,long revision){public Selection{if(!dev.mineagent.runtime.core.agent.BuiltinAgentSkins.valid(skin)||player==null||revision<0)throw new IllegalArgumentException("AGENT_SKIN_SELECTION");}}
    public AgentSkinPayload{skins=Map.copyOf(skins);if(skins.size()>64)throw new IllegalArgumentException("AGENT_SKIN_COUNT");}
    public static final Type<AgentSkinPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","agent_skins"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AgentSkinPayload> CODEC=CustomPacketPayload.codec((v,b)->{b.writeVarInt(v.skins.size());v.skins.forEach((id,s)->{b.writeUUID(id);b.writeUtf(s.skin,40);b.writeUUID(s.player);b.writeVarLong(s.revision);});},b->{int n=b.readVarInt();if(n<0||n>64)throw new IllegalArgumentException("SKIN_COUNT");var out=new LinkedHashMap<UUID,Selection>();for(int i=0;i<n;i++)if(out.put(b.readUUID(),new Selection(b.readUtf(40),b.readUUID(),b.readVarLong()))!=null)throw new IllegalArgumentException("SKIN_DUPLICATE");return new AgentSkinPayload(out);});
    public Type<AgentSkinPayload> type(){return TYPE;}
}
