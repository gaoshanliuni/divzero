package dev.mineagent.runtime.neoforge.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Only a reviewed data plan crosses the wire. No executable code or alternate target player is accepted. */
public final class PlayerBodyPayloads {
    private PlayerBodyPayloads(){}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String id){return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime",id));}
    public record Offer(UUID operation,UUID world,UUID player,String dimension,String agent,String plan) implements CustomPacketPayload {
        public static final Type<Offer> TYPE=PlayerBodyPayloads.type("player_body_offer");
        public static final StreamCodec<RegistryFriendlyByteBuf,Offer> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation);b.writeUUID(p.world);b.writeUUID(p.player);b.writeUtf(p.dimension,256);b.writeUtf(p.agent,128);b.writeUtf(p.plan,16000);},b->new Offer(b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(256),b.readUtf(128),b.readUtf(16000)));
        public Type<Offer> type(){return TYPE;}
    }
    public record Decision(UUID operation,UUID consent,String action,int completedSteps,String reason) implements CustomPacketPayload {
        public static final Type<Decision> TYPE=PlayerBodyPayloads.type("player_body_decision");
        public static final StreamCodec<RegistryFriendlyByteBuf,Decision> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation);b.writeUUID(p.consent);b.writeUtf(p.action,16);b.writeVarInt(p.completedSteps);b.writeUtf(p.reason,64);},b->new Decision(b.readUUID(),b.readUUID(),b.readUtf(16),b.readVarInt(),b.readUtf(64)));
        public Decision{if(!Set.of("START","STOP","HEARTBEAT","FINISH").contains(action)||completedSteps<0||completedSteps>16||reason==null||!reason.matches("[A-Z0-9_]{0,64}"))throw new IllegalArgumentException("PLAYER_BODY_EVENT");}
        public Type<Decision> type(){return TYPE;}
    }
    public record Signal(UUID operation,UUID consent,String action,long sequence,String detail) implements CustomPacketPayload {
        public static final Type<Signal> TYPE=PlayerBodyPayloads.type("player_body_signal");
        public static final StreamCodec<RegistryFriendlyByteBuf,Signal> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation);b.writeUUID(p.consent);b.writeUtf(p.action,16);b.writeVarLong(p.sequence);b.writeUtf(p.detail,128);},b->new Signal(b.readUUID(),b.readUUID(),b.readUtf(16),b.readVarLong(),b.readUtf(128)));
        public Type<Signal> type(){return TYPE;}
    }
}
