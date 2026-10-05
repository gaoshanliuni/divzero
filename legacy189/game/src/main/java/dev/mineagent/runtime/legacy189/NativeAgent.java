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
    public NativeAgent(MinecraftServer server, WorldServer world, NativeWorldData.AgentDefinition definition) {
        super(server, world, new GameProfile(definition.id, "DZ" + definition.id.toString().replace("-", "").substring(0, 14)), new ItemInWorldManager(world));
        owner = definition.owner; displayName = definition.name;
        playerNetServerHandler = new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), this) {
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
        onUpdateEntity();
    }
    public void stopActions() { moveForward = 0; moveStrafing = 0; setSprinting(false); setSneaking(false); clearItemInUse(); }
}
