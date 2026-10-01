package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.MaceItem;

/** A real descending smash window can end before full cooldown. Vanilla computes its actual reduced base and fall bonus. */
public final class NativeAttackReadiness {
    public static boolean fallingSmash(Player player){return player.getMainHandItem().getItem() instanceof MaceItem&&MaceItem.canSmashAttack(player);}
    public static boolean preparingFall(Player player,net.minecraft.world.entity.LivingEntity target){
        if(MaceItem.canSmashAttack(player))return true;
        if(target==null||player.getY()-target.getY()<=2||player.isFallFlying()||player.getAbilities().flying||player.isNoGravity())return false;
        var motion=player instanceof net.minecraft.server.level.ServerPlayer server&&!(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)?server.getKnownMovement():player.getDeltaMovement();
        return motion.y<=.001&&(!player.onGround()||player.level().noCollision(player,player.getBoundingBox().move(0,-.08,0)));
    }
    public static boolean ready(Player player){return player.getAttackStrengthScale(.5f)>=.95f||fallingSmash(player)&&player.getAttackStrengthScale(.5f)>=.1f;}
    private NativeAttackReadiness(){}
}
