package dev.mineagent.runtime.neoforge.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;
public final class ProviderModelsPayloads {
    private ProviderModelsPayloads(){}
    public record Request(UUID request,UUID world,UUID instance,boolean refresh,int offset,String query) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","provider_models_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.request);b.writeUUID(p.world);b.writeUUID(p.instance);b.writeBoolean(p.refresh);b.writeVarInt(p.offset);b.writeUtf(p.query,128);},b->new Request(b.readUUID(),b.readUUID(),b.readUUID(),b.readBoolean(),b.readVarInt(),b.readUtf(128)));
        public Type<Request> type(){return TYPE;}
    }
    public record Response(UUID request,UUID world,UUID instance,String state) implements CustomPacketPayload {
        public static final Type<Response> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","provider_models_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Response> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.request);b.writeUUID(p.world);b.writeUUID(p.instance);b.writeUtf(p.state,32768);},b->new Response(b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(32768)));
        public Type<Response> type(){return TYPE;}
    }
    public static void respond(Request r,net.minecraft.server.level.ServerPlayer p,java.util.function.Consumer<CustomPacketPayload> reply){
        var s=p.level().getServer();String state;
        try{if(!r.world.equals(dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.worldId(s))||!r.instance.equals(dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.config(s).instanceId()))throw new IllegalStateException("MODELS_CONTEXT_CHANGED");state=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dev.mineagent.runtime.neoforge.ui.ServerProviderModels.view(p,r.refresh,r.offset,r.query));}
        catch(Exception error){String code=java.util.Objects.toString(error.getMessage(),"");if(!code.matches("MODELS_[A-Z_]{1,40}"))code="MODELS_UNAVAILABLE";state="{\"status\":\"ERROR\",\"error\":\""+code+"\",\"models\":[]}";}
        reply.accept(new Response(r.request,r.world,r.instance,state));
    }
}
