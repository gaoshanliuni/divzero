package dev.mineagent.runtime.neoforge.body;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class MineAgentConnection extends Connection {
    private static final SocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private final EmbeddedChannel channel = new EmbeddedChannel();
    private volatile PacketListener listener;
    private volatile boolean connected = true;
    @FunctionalInterface public interface PreparedPlayerFactory {
        net.minecraft.server.level.ServerPlayer create(net.minecraft.server.MinecraftServer server,
                net.minecraft.server.level.ServerLevel level,com.mojang.authlib.GameProfile profile,
                net.minecraft.server.level.ClientInformation information);
    }
    private final PreparedPlayerFactory preparedPlayerFactory;

    public MineAgentConnection() {
        this(null);
    }
    public MineAgentConnection(PreparedPlayerFactory factory) {
        super(PacketFlow.SERVERBOUND);
        preparedPlayerFactory=factory;
    }
    public net.minecraft.server.level.ServerPlayer createPreparedPlayer(net.minecraft.server.MinecraftServer server,
            net.minecraft.server.level.ServerLevel level,com.mojang.authlib.GameProfile profile,net.minecraft.server.level.ClientInformation information){
        if(preparedPlayerFactory==null)throw new IllegalStateException("AI_PREPARED_SPAWN_FACTORY_MISSING");
        return preparedPlayerFactory.create(server,level,profile,information);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
        listener = packetListener;
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {
        listener = packetListener;
    }

    @Override
    public PacketListener getPacketListener() {
        return listener;
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        // The AI body has no remote client. Real observers receive tracking packets from the server.
    }

    @Override
    public void disconnect(Component reason) {
        connected = false;
        channel.close();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public void flushChannel() {
        // There is no remote socket to flush.
    }

    @Override
    public String getLoggableAddress(boolean logIps) {
        return "mineagent-local";
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
    }
}
