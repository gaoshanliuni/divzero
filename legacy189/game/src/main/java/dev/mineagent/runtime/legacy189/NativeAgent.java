package dev.mineagent.runtime.legacy189;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import java.util.UUID;

/** A registered, identifiable player body. A clientless packet sink never queues packets. */
public final class NativeAgent extends EntityPlayerMP {
    public final UUID owner;
    public final String displayName;
    private long lastPhysicsTick = Long.MIN_VALUE;
    private long lastJumpTick = Long.MIN_VALUE;
    private boolean bowUseSent;
    private final ClientlessConnection connection;
    private LegacyPolicy policy = LegacyPolicy.get();
    public double decisionRisk;
    public boolean sprint45Requested;
    public int sprint45Ticks;
    public final LegacyLoadoutController loadout=new LegacyLoadoutController();
    public final LegacyProjectileGuard projectileGuard=new LegacyProjectileGuard();
    public LegacyPolicy policy() { return policy; }
    public void policy(LegacyPolicy value) { if (value == null) throw new IllegalArgumentException("POLICY_MISSING"); policy = value; }
    public NativeAgent(MinecraftServer server, WorldServer world, NativeWorldData.AgentDefinition definition) {
        super(server, world, new GameProfile(definition.id, "DZ" + definition.id.toString().replace("-", "").substring(0, 14)), new ItemInWorldManager(world));
        owner = definition.owner; displayName = definition.name;
        connection = new ClientlessConnection();
        playerNetServerHandler = new NetHandlerPlayServer(server, connection, this) {
            @Override public void sendPacket(Packet packet) { /* no physical client, no unbounded outbound queue */ }
            @Override public void kickPlayerFromServer(String reason) { NativeRuntime.removeBody(NativeAgent.this); }
        };
        setPosition(definition.x, definition.y, definition.z);
    }
    @Override public IChatComponent getDisplayName() { return new ChatComponentText(displayName); }
    /** Normally a real client's movement packet drives this method; our body needs one physics step per server tick. */
    public void physics(long tick) {
        if (lastPhysicsTick == tick || isDead) return;
        lastPhysicsTick = tick;
        double previousY = posY; net.minecraft.world.World previousWorld = worldObj;
        LegacySprintSteering.apply(this);sprint45Requested=false;
        onUpdateEntity();
        // Vanilla MP players normally receive this calculation from their movement
        // packets. Use the displacement actually produced by our native physics.
        if (worldObj == previousWorld) handleFalling(posY - previousY, onGround);
        boolean drawing=isUsingItem()&&getItemInUse().getItem() instanceof net.minecraft.item.ItemBow;
        if(drawing||bowUseSent){if(drawing!=bowUseSent||tick%4==0)NativeNetwork.bowUse(this);bowUseSent=drawing;}
    }
    @Override public void clearItemInUse(){
        super.clearItemInUse();
        if(bowUseSent){NativeNetwork.bowUse(this);bowUseSent=false;}
    }
    public void stopActions() { sprint45Requested=false;moveForward = 0; moveStrafing = 0; setSprinting(false); setSneaking(false); clearItemInUse(); }
    public boolean requestJump() {
        long tick = MinecraftServer.getServer().getTickCounter();
        if (!onGround || !isEntityAlive() || tick == lastJumpTick) return false;
        lastJumpTick = tick; jump(); return true;
    }
    public void closeConnection() { connection.closeChannel(new ChatComponentText("AI body closed")); }
    /** Forge may query channel attributes directly, bypassing NetHandler.sendPacket.
     * An empty real channel safely reports no FML dispatcher for this clientless body.
     */
    private static final class ClientlessConnection extends NetworkManager {
        private final io.netty.channel.embedded.EmbeddedChannel sink = new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelDuplexHandler() {
            @Override public void write(io.netty.channel.ChannelHandlerContext context, Object message, io.netty.channel.ChannelPromise promise) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.setSuccess();
            }
        });
        ClientlessConnection() { super(EnumPacketDirection.SERVERBOUND); }
        @Override public io.netty.channel.Channel channel() { return sink; }
        @Override public boolean isChannelOpen() { return sink.isOpen(); }
        @Override public void sendPacket(Packet packet) { }
        @Override public void closeChannel(IChatComponent reason) { sink.close(); }
    }
}
