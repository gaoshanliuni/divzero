package dev.mineagent.runtime.neoforge.network;
import dev.mineagent.runtime.api.scoreboard.WorldBoardFrame;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;
public record WorldBoardPayload(WorldBoardFrame frame) implements CustomPacketPayload {
    public WorldBoardPayload{Objects.requireNonNull(frame);}
    public static final Type<WorldBoardPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime","world_boards"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WorldBoardPayload> CODEC=CustomPacketPayload.codec((p,b)->{
        var f=p.frame();b.writeUUID(f.serverInstanceId());b.writeUUID(f.worldId());b.writeUUID(f.viewerId());b.writeVarLong(f.sequence());b.writeUtf(f.dimension(),128);b.writeVarInt(f.boards().size());
        for(var v:f.boards()){b.writeUUID(v.viewId());b.writeUUID(v.packageId());b.writeVarLong(v.revision());b.writeDouble(v.x());b.writeDouble(v.y());b.writeDouble(v.z());b.writeFloat(v.yaw());b.writeFloat(v.scale());b.writeUtf(v.text(),4096);}
    },b->{
        UUID server=b.readUUID(),world=b.readUUID(),viewer=b.readUUID();long sequence=b.readVarLong();String dimension=b.readUtf(128);int count=b.readVarInt();
        if(count<0||count>16)throw new IllegalArgumentException("WORLD_BOARD_BUDGET");var boards=new ArrayList<WorldBoardFrame.Board>();
        for(int i=0;i<count;i++)boards.add(new WorldBoardFrame.Board(b.readUUID(),b.readUUID(),b.readVarLong(),b.readDouble(),b.readDouble(),b.readDouble(),b.readFloat(),b.readFloat(),b.readUtf(4096)));
        return new WorldBoardPayload(new WorldBoardFrame(server,world,viewer,sequence,dimension,boards));
    });
    @Override public Type<WorldBoardPayload> type(){return TYPE;}
}
