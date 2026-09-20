package dev.mineagent.runtime.neoforge.network;
import dev.mineagent.runtime.core.objects.RuntimeModelBundle;
import dev.mineagent.runtime.neoforge.content.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
public final class ObjectAssetPayloads {
    private ObjectAssetPayloads(){}
    public record Request(UUID request,int entity,String hash,int offset) implements CustomPacketPayload {
        public Request{if(request==null||entity<0||hash==null||!hash.matches("[a-f0-9]{64}")||offset<0||offset>=RuntimeModelBundle.MAX_BYTES)throw new IllegalArgumentException("OBJECT_ASSET_REQUEST");}
        public static final Type<Request> TYPE=new Type<>(Identifier.parse("mineagent_runtime:object_asset_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.request);b.writeVarInt(p.entity);b.writeUtf(p.hash,64);b.writeVarInt(p.offset);},b->new Request(b.readUUID(),b.readVarInt(),b.readUtf(64),b.readVarInt()));
        @Override public Type<Request> type(){return TYPE;}
    }
    public record Chunk(UUID request,int entity,String hash,int total,int offset,byte[] bytes,String error) implements CustomPacketPayload {
        public Chunk{if(request==null||entity<0||hash==null||!hash.matches("[a-f0-9]{64}")||total<0||total>RuntimeModelBundle.MAX_BYTES||offset<0||offset>total||bytes==null||bytes.length>24576||bytes.length>total-offset||error==null||!error.matches("[A-Z0-9_]{0,64}"))throw new IllegalArgumentException("OBJECT_ASSET_CHUNK");bytes=bytes.clone();}
        public static final Type<Chunk> TYPE=new Type<>(Identifier.parse("mineagent_runtime:object_asset_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Chunk> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.request);b.writeVarInt(p.entity);b.writeUtf(p.hash,64);b.writeVarInt(p.total);b.writeVarInt(p.offset);b.writeByteArray(p.bytes);b.writeUtf(p.error,64);},b->new Chunk(b.readUUID(),b.readVarInt(),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readByteArray(24576),b.readUtf(64)));
        @Override public Type<Chunk> type(){return TYPE;}
    }
    private static final Map<ServerPlayer,long[]> rate=new WeakHashMap<>();
    public static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("object-assets-1");r.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->send((ServerPlayer)c.player(),p)));r.playToClient(Chunk.TYPE,Chunk.CODEC);}
    private static void send(ServerPlayer viewer,Request p){
        String error="OBJECT_ASSET_UNAVAILABLE";
        try{
            if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(viewer.level().getServer()))throw new IllegalStateException("WORLD_IDENTITY_NOT_READY");
            if(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)throw new SecurityException();long tick=viewer.level().getServer().getTickCount();var window=rate.computeIfAbsent(viewer,k->new long[]{tick,0});if(tick-window[0]>=20){window[0]=tick;window[1]=0;}if(++window[1]>96)throw new IllegalStateException("OBJECT_RATE_LIMIT");
            var entity=viewer.level().getEntity(p.entity());if(!(entity instanceof RuntimeObjectEntity object)||object.header()==null||!object.header().asset().equals(p.hash())||viewer.distanceToSqr(object)>128*128)throw new SecurityException();
            var bundle=WorldContentRuntime.get(viewer.level().getServer()).objectBundle(object);var bytes=bundle.chunk(p.offset(),24576);PacketDistributor.sendToPlayer(viewer,new Chunk(p.request(),p.entity(),p.hash(),bundle.size(),p.offset(),bytes,""));return;
        }catch(Exception failed){if("OBJECT_RATE_LIMIT".equals(failed.getMessage()))error="OBJECT_RATE_LIMIT";}
        PacketDistributor.sendToPlayer(viewer,new Chunk(p.request(),p.entity(),p.hash(),0,0,new byte[0],error));
    }
}
