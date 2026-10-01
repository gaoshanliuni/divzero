package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;

/** Prepare real equipment; activation, consumption, effects and damage remain in the vanilla player pipeline. */
public final class NativeEquipmentSupport {
    public static boolean protects(ItemStack stack){return !stack.isEmpty()&&stack.has(DataComponents.DEATH_PROTECTION);}
    public static int protectionSlot(ServerPlayer player){
        if(protects(player.getMainHandItem())||protects(player.getOffhandItem()))return -1;
        for(int slot=0;slot<36;slot++)if(protects(player.getInventory().getItem(slot)))return slot;
        return -1;
    }
    public static void maintainAi(dev.mineagent.runtime.neoforge.body.MineAgentPlayer player){
        if(!player.canAct()||player.isUsingItem()&&player.getUsedItemHand()==InteractionHand.MAIN_HAND||!player.inventoryMenu.getCarried().isEmpty())return;
        int slot=protectionSlot(player);if(slot<0)return;if(player.isUsingItem())player.stopUsingItem();
        var carried=player.getInventory().getItem(slot);var previous=player.getOffhandItem();
        player.setItemInHand(InteractionHand.OFF_HAND,carried);player.getInventory().setItem(slot,previous);player.inventoryMenu.broadcastChanges();
    }
    static boolean maintainControlled(SkillWork work){
        int slot=protectionSlot(work.player());if(slot<0||work.player().isUsingItem())return false;
        work.actor.equipOffhand(work.token(),slot);work.session.add("nativeProtectionEquipRequests",1);return true;
    }
    private NativeEquipmentSupport(){}
}
