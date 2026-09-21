package dev.mineagent.runtime.neoforge.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;
public final class AutonomyPayloads {
    private AutonomyPayloads(){}
    public record Offer(UUID session,UUID player,UUID world,String dimension,String agent,String goal) implements CustomPacketPayload {
        public static final Type<Offer> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","autonomy_offer"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Offer> CODEC=CustomPacketPayload.codec((v,b)->{b.writeUUID(v.session);b.writeUUID(v.player);b.writeUUID(v.world);b.writeUtf(v.dimension,256);b.writeUtf(v.agent,128);b.writeUtf(v.goal,4096);},b->new Offer(b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(256),b.readUtf(128),b.readUtf(4096)));
        public Type<Offer> type(){return TYPE;}
    }
    public record Input(UUID session,UUID consent,String kind,boolean paused) implements CustomPacketPayload {
        public static final Type<Input> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","autonomy_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=CustomPacketPayload.codec((v,b)->{b.writeUUID(v.session);b.writeUUID(v.consent);b.writeUtf(v.kind,16);b.writeBoolean(v.paused);},b->new Input(b.readUUID(),b.readUUID(),b.readUtf(16),b.readBoolean()));
        public Input{if(!Set.of("START","STOP","HEARTBEAT").contains(kind))throw new IllegalArgumentException("AUTONOMY_INPUT");}public Type<Input> type(){return TYPE;}
    }
    public record Frame(UUID session,UUID consent,long sequence,String data) implements CustomPacketPayload {
        public static final Type<Frame> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","autonomy_frame"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Frame> CODEC=CustomPacketPayload.codec((v,b)->{b.writeUUID(v.session);b.writeUUID(v.consent);b.writeVarLong(v.sequence);b.writeUtf(v.data,12000);},b->new Frame(b.readUUID(),b.readUUID(),b.readVarLong(),b.readUtf(12000)));
        public Type<Frame> type(){return TYPE;}
    }
}
