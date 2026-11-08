package dev.mineagent.runtime.neoforge.content;

import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

@EventBusSubscriber(modid="mineagent_runtime")
public final class CreatureTriggerEvents {
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void projectile(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event){
        if(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit&&hit.getEntity() instanceof RuntimeCreatureEntity creature&&!creature.level().isClientSide()){
            var source=event.getProjectile();creature.trigger("projectile_hit",source.getOwner() instanceof LivingEntity living?living:null,ItemStack.EMPTY,net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()).toString());
        }
    }
    @SubscribeEvent
    public static void hurt(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event){
        if(event.getEntity() instanceof RuntimeCreatureEntity creature&&!creature.level().isClientSide()&&event.getInflictedDamage()>0)
            creature.trigger("hurt",event.getSource().getEntity() instanceof LivingEntity living?living:null,ItemStack.EMPTY,"");
    }
    private CreatureTriggerEvents(){}
}
