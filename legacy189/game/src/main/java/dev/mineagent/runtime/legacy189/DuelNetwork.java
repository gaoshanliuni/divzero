package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import java.nio.charset.StandardCharsets;

public final class DuelNetwork {
    private DuelNetwork() { }
    public static void register() {
        NativeNetwork.CHANNEL.registerMessage(RequestHandler.class, Request.class, 2, Side.SERVER);
        NativeNetwork.CHANNEL.registerMessage(StateHandler.class, State.class, 3, Side.CLIENT);
    }
    public static void send(EntityPlayerMP player, JsonObject state) { if (!(player instanceof NativeAgent)) NativeNetwork.CHANNEL.sendTo(new State(state.toString()), player); }
    public static abstract class Message implements IMessage {
        public String json;
        protected Message() { }
        protected Message(String json) { this.json = json; }
        @Override public void fromBytes(ByteBuf buffer) {
            int size = buffer.readInt(); if (size < 2 || size > 49152 || size != buffer.readableBytes()) throw new IllegalArgumentException("DUEL_PACKET_SIZE");
            byte[] bytes = new byte[size]; buffer.readBytes(bytes); json = new String(bytes, StandardCharsets.UTF_8);
        }
        @Override public void toBytes(ByteBuf buffer) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8); if (bytes.length > 49152) throw new IllegalArgumentException("DUEL_PACKET_SIZE");
            buffer.writeInt(bytes.length); buffer.writeBytes(bytes);
        }
    }
    public static final class Request extends Message { public Request() { } public Request(String value) { super(value); } }
    public static final class State extends Message { public State() { } public State(String value) { super(value); } }
    public static final class StateHandler implements IMessageHandler<State, IMessage> {
        @Override public IMessage onMessage(State message, MessageContext context) { LegacyMod.proxy.duelState(message.json); return null; }
    }
    public static final class RequestHandler implements IMessageHandler<Request, IMessage> {
        @Override public IMessage onMessage(final Request message, MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().playerEntity;
            player.getServerForPlayer().addScheduledTask(() -> {
                if (MinecraftServer.getServer().getConfigurationManager().getPlayerByUUID(player.getUniqueID()) != player) return;
                try {
                    JsonObject value = new JsonParser().parse(message.json).getAsJsonObject();
                    if (!NativeDuel.allowed(player) || !value.get("world").getAsString().equals(NativeRuntime.data().identity().toString())
                            || !value.get("session").getAsString().equals(NativeRuntime.session(player).toString())) throw new IllegalStateException("对局会话已变化");
                    String action = value.get("action").getAsString();
                    if (action.equals("choose")) NativeDuel.choose(player, value.get("revision").getAsLong(), value.get("actor").getAsString(),
                            value.has("slot") ? value.get("slot").getAsInt() : -1, value.has("item") ? value.get("item").getAsString() : "", value.has("wool") ? value.get("wool").getAsBoolean() : null);
                    else if (action.equals("ready") || action.equals("stop") || action.equals("equip")) {
                        if (value.get("revision").getAsLong() != NativeDuel.session(player).revision) throw new IllegalStateException("对局状态已变化");
                        NativeDuel.command(player, action);
                    } else throw new IllegalArgumentException("未知对局操作");
                } catch (Exception failure) {
                    player.addChatMessage(new ChatComponentText("[DivZero] 对局操作未生效：" + (failure.getMessage() == null ? "请重新打开面板" : failure.getMessage())));
                    if (NativeDuel.allowed(player)) send(player, NativeDuel.snapshot(NativeDuel.session(player), false));
                }
            });
            return null;
        }
    }
}
