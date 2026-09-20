package dev.mineagent.runtime.neoforge.network;

import dev.mineagent.runtime.core.conversation.SpeechWav;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

public final class SpeechInputPayloads {
    private SpeechInputPayloads(){}
    public record Command(UUID operation,UUID world,UUID agent,UUID conversation,UUID context,long revision,String action,String sha256,int audioBytes,int index,int count,byte[] data) implements CustomPacketPayload{
        public Command{Objects.requireNonNull(operation);Objects.requireNonNull(world);Objects.requireNonNull(agent);Objects.requireNonNull(conversation);Objects.requireNonNull(context);
            if(revision<0||!Set.of("begin","chunk","status","cancel","discard").contains(action)||sha256==null||!sha256.matches("(?:[a-f0-9]{64})?")||audioBytes<0||audioBytes>SpeechWav.MAX_BYTES||count<0||count>40||index<0||index>39||data==null||data.length>SpeechWav.CHUNK_BYTES||!action.equals("chunk")&&data.length!=0)throw new IllegalArgumentException("ASR_PACKET_INVALID");data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        public static final Type<Command> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","speech_input_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Command> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation);b.writeUUID(p.world);b.writeUUID(p.agent);b.writeUUID(p.conversation);b.writeUUID(p.context);b.writeVarLong(p.revision);b.writeUtf(p.action,16);b.writeUtf(p.sha256,64);b.writeVarInt(p.audioBytes);b.writeVarInt(p.index);b.writeVarInt(p.count);b.writeByteArray(p.data);},b->new Command(b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readVarLong(),b.readUtf(16),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readByteArray(SpeechWav.CHUNK_BYTES)));
        @Override public Type<Command> type(){return TYPE;}
    }
    public record Reply(UUID operation,UUID world,UUID agent,UUID conversation,UUID context,String state,String errorCode,String text,String model,boolean mayUpload) implements CustomPacketPayload{
        public Reply(UUID operation,UUID world,UUID agent,UUID conversation,UUID context,String state,String errorCode,String text,String model){this(operation,world,agent,conversation,context,state,errorCode,text,model,false);}
        public Reply{Objects.requireNonNull(operation);Objects.requireNonNull(world);Objects.requireNonNull(agent);Objects.requireNonNull(conversation);Objects.requireNonNull(context);if(state==null||!state.matches("[A-Z_]{1,32}")||errorCode==null||!errorCode.matches("[A-Z0-9_]{0,80}")||text==null||text.length()>16384||model==null||model.length()>256)throw new IllegalArgumentException("ASR_REPLY_INVALID");}
        public static final Type<Reply> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","speech_result_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation);b.writeUUID(p.world);b.writeUUID(p.agent);b.writeUUID(p.conversation);b.writeUUID(p.context);b.writeUtf(p.state,32);b.writeUtf(p.errorCode,80);b.writeUtf(p.text,16384);b.writeUtf(p.model,256);b.writeBoolean(p.mayUpload);},b->new Reply(b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(32),b.readUtf(80),b.readUtf(16384),b.readUtf(256),b.readBoolean()));
        @Override public Type<Reply> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("speech-input-1").versioned("1").executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK);
        r.playToServer(Command.TYPE,Command.CODEC,(p,c)->c.enqueueWork(()->dev.mineagent.runtime.neoforge.ui.ServerSpeechInput.handle((net.minecraft.server.level.ServerPlayer)c.player(),p)));
        r.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->{var source=c.connection();c.enqueueWork(()->dev.mineagent.runtime.neoforge.client.screen.NativeSpeechScreen.accept(p,source));});
    }
}
