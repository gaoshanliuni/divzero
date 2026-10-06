package dev.mineagent.runtime.neoforge.body;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.core.BlockPos;

/** Modern authoritative inventory + selected slot. Call after the native transaction returns. */
public final class NativeInventorySync {
    public static void full(ServerPlayer p){
        p.getInventory().setChanged();p.inventoryMenu.broadcastFullState();
        if(p.containerMenu!=p.inventoryMenu)p.containerMenu.broadcastFullState();
        p.connection.send(new ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
    }
    public static void blockUse(ServerPlayer p,BlockPos anchor,BlockPos destination){
        full(p);p.connection.send(new ClientboundBlockUpdatePacket(p.level(),anchor));
        p.connection.send(new ClientboundBlockUpdatePacket(p.level(),destination));
    }
    private NativeInventorySync(){}
}
