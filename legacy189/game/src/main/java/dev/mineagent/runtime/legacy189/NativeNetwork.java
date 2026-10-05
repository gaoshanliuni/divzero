package dev.mineagent.runtime.legacy189;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import java.util.UUID;

public final class NativeNetwork {
    public static final int PROTOCOL = 3;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("divzero189");
    private NativeNetwork() { }
    public static void initialize() {
        CHANNEL.registerMessage(ActionHandler.class, Action.class, 0, Side.SERVER);
        CHANNEL.registerMessage(StateHandler.class, State.class, 1, Side.CLIENT);
        NetworkRegistry.INSTANCE.registerGuiHandler(LegacyMod.instance, new NativeGuiHandler());
    }
    public static void sync(EntityPlayerMP player) {
        if (player instanceof NativeAgent || player.worldObj.isRemote) return;
        NativeOffhand hand = NativeOffhand.get(player);
        CHANNEL.sendTo(new State(NativeRuntime.data().identity(), NativeRuntime.session(player), player.getUniqueID(), player.dimension,
                hand.revision(), hand.stack(), NativeRuntime.enabled(player), NativeRuntime.data().modernCombat()), player);
    }
    private static UUID uuid(ByteBuf input) { return new UUID(input.readLong(), input.readLong()); }
    private static void uuid(ByteBuf output, UUID value) { output.writeLong(value.getMostSignificantBits()); output.writeLong(value.getLeastSignificantBits()); }
    public static class Action implements IMessage {
        public UUID world, session;
        public int kind;
        public long revision;
        public Action() { }
        public Action(UUID world, UUID session, int kind, long revision) { this.world = world; this.session = session; this.kind = kind; this.revision = revision; }
        @Override public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() != 43 || buffer.readUnsignedShort() != PROTOCOL) throw new IllegalArgumentException("LEGACY_ACTION_FRAME");
            world = uuid(buffer); session = uuid(buffer); kind = buffer.readUnsignedByte(); revision = buffer.readLong();
            if (kind > 1 || revision < 0) throw new IllegalArgumentException("LEGACY_ACTION_BOUNDS");
        }
        @Override public void toBytes(ByteBuf buffer) { buffer.writeShort(PROTOCOL); uuid(buffer, world); uuid(buffer, session); buffer.writeByte(kind); buffer.writeLong(revision); }
    }
    public static class State implements IMessage {
        public UUID world, session, owner;
        public int dimension;
        public long revision;
        public ItemStack offhand;
        public boolean enabled, modern;
        public State() { }
        public State(UUID world, UUID session, UUID owner, int dimension, long revision, ItemStack stack, boolean enabled, boolean modern) {
            this.world = world; this.session = session; this.owner = owner; this.dimension = dimension;
            this.revision = revision; offhand = stack == null ? null : stack.copy(); this.enabled = enabled;
            this.modern = modern;
        }
        @Override public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() > 2 * 1024 * 1024 || buffer.readUnsignedShort() != PROTOCOL) throw new IllegalArgumentException("LEGACY_STATE_FRAME");
            world = uuid(buffer); session = uuid(buffer); owner = uuid(buffer); dimension = buffer.readInt(); revision = buffer.readLong();
            enabled = buffer.readBoolean(); modern = buffer.readBoolean(); offhand = ByteBufUtils.readItemStack(buffer);
            if (revision < 0 || buffer.isReadable()) throw new IllegalArgumentException("LEGACY_STATE_BOUNDS");
        }
        @Override public void toBytes(ByteBuf buffer) {
            buffer.writeShort(PROTOCOL); uuid(buffer, world); uuid(buffer, session); uuid(buffer, owner);
            buffer.writeInt(dimension); buffer.writeLong(revision); buffer.writeBoolean(enabled); buffer.writeBoolean(modern); ByteBufUtils.writeItemStack(buffer, offhand);
        }
    }
    public static class ActionHandler implements IMessageHandler<Action, IMessage> {
        @Override public IMessage onMessage(final Action message, final MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().playerEntity;
            player.getServerForPlayer().addScheduledTask(new Runnable() {
                @Override public void run() {
                    if (player.isDead || !player.isEntityAlive()
                            || net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().getPlayerByUUID(player.getUniqueID()) != player
                            || !NativeRuntime.data().identity().equals(message.world) || !NativeRuntime.session(player).equals(message.session)) return;
                    try {
                        if (message.kind == 0) NativeOffhand.get(player).swap(message.revision);
                        else player.openGui(LegacyMod.instance, 0, player.worldObj, 0, 0, 0);
                    } catch (RuntimeException failure) {
                        player.addChatMessage(new net.minecraft.util.ChatComponentText("[DivZero] " + failure.getMessage()));
                    }
                    sync(player);
                }
            });
            return null;
        }
    }
    public static class StateHandler implements IMessageHandler<State, IMessage> {
        @Override public IMessage onMessage(State message, MessageContext context) { LegacyMod.proxy.receive(message); return null; }
    }
}
