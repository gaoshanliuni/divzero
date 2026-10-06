package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBow;

/** Identical native action driver for a human duel and either side of model self-play. */
public final class LegacyCombatDriver {
    private LegacyCombatDriver(){}
    public static void tick(NativeAgent actor,EntityPlayerMP target,ArenaNavigator navigation,LegacyMeleeController melee,LegacyRangedController ranged,int tick){
        actor.decisionRisk=0;
        actor.sprint45Requested=false;
        if(actor.loadout.tick(actor,target,navigation,tick))return;
        double dx=target.posX-actor.posX,dz=target.posZ-actor.posZ,dy=target.posY+target.getEyeHeight()*.8-actor.posY-actor.getEyeHeight();
        actor.rotationYaw=(float)Math.toDegrees(Math.atan2(dz,dx))-90;actor.rotationYawHead=actor.rotationYaw;actor.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        if(actor.getDistanceToEntity(target)>6&&actor.projectileGuard.evade(actor,target,tick))return;
        if(navigation.recovering()){
            if(ModernCombat.reachable(actor,target))navigation.stop(actor);
            else{navigation.move(actor,target,tick,false);return;}
        }
        if(actor.getHeldItem()!=null&&actor.getHeldItem().getItem() instanceof ItemBow)ranged.tick(actor,target,navigation,tick);
        else melee.tick(actor,target,navigation,tick);
        if(actor.getDistanceToEntity(target)>4.3&&actor.isSprinting()&&!navigation.recovering())actor.sprint45Requested=true;
    }
}
