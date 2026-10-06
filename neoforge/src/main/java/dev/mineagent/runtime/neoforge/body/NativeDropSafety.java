package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.TraversalSafety;
import dev.mineagent.runtime.neoforge.mixin.NativeFallDamageAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.phys.Vec3;

/** Read-only estimate. Never call hurt or damage absorption hooks merely to plan a route. */
public final class NativeDropSafety {
    public static double damage(ServerPlayer p,double height){
        if(height<=0)return 0;
        // Invoke this version's native safe-fall attributes / rounding (not the legacy ceil formula).
        float damage=Math.max(0,((NativeFallDamageAccess)p).divzero$fallDamage(height,1));
        var resistance=p.getEffect(MobEffects.RESISTANCE);
        if(resistance!=null)damage*=Math.max(0,1-(resistance.getAmplifier()+1)*.2f);
        float protection=EnchantmentHelper.getDamageProtection(p.level(),p,p.damageSources().fall());
        return CombatRules.getDamageAfterMagicAbsorb(damage,protection);
    }
    public static boolean affordable(ServerPlayer p,double height){return TraversalSafety.affordableDrop(height,damage(p,height),p.getHealth(),p.getAbsorptionAmount());}
    public static int maximum(ServerPlayer p){int height=3;while(height<24&&affordable(p,height+1))height++;return height;}
    public static boolean landing(ServerPlayer p,Vec3 at){
        // Geometry/fluid checks are performed by the evaluator. Reject a new multi-enemy pocket.
        var box=p.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(at).inflate(3,2,3);
        return p.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,box,e->e!=p&&e.isAlive()
                &&(e instanceof net.minecraft.world.entity.monster.Enemy||e==p.getLastHurtByMob()
                ||e instanceof net.minecraft.world.entity.player.Player other&&!other.isCreative()&&!other.isSpectator()&&dev.mineagent.runtime.neoforge.skill.NativeHumanDuel.pvpParticipant(p))).size()<=1;
    }
    private NativeDropSafety(){}
}
