package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.MaceItem;

/** A real descending smash window can end before full cooldown. Vanilla computes its actual reduced base and fall bonus. */
public final class NativeAttackReadiness {
    public static boolean fallingSmash(Player player){return player.getMainHandItem().getItem() instanceof MaceItem&&MaceItem.canSmashAttack(player)&&player.getDeltaMovement().y<0;}
    public static boolean ready(Player player){return player.getAttackStrengthScale(.5f)>=.95f||fallingSmash(player)&&player.getAttackStrengthScale(.5f)>=.1f;}
    private NativeAttackReadiness(){}
}
