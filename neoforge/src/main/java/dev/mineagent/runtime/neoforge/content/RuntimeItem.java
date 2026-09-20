package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.core.objects.RuntimeItemBinding;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

/** One registry carrier; each item uses its owning signed package's model and event handler. */
public final class RuntimeItem extends Item {
    public RuntimeItem(Properties properties){super(properties);}
    public static RuntimeItemBinding binding(ItemStack stack){try{return stack.is(MineAgentRegistries.RUNTIME_ITEM.get())?RuntimeItemBinding.parse(stack.get(MineAgentRegistries.RUNTIME_ITEM_BINDING.get())):null;}catch(IllegalArgumentException invalid){return null;}}
    @Override public int getUseDuration(ItemStack stack,net.minecraft.world.entity.LivingEntity user){var b=binding(stack);return b!=null&&b.chargeTicks()>0?dev.mineagent.runtime.core.objects.ItemThrowMath.USE_DURATION:0;}
    @Override public ItemUseAnimation getUseAnimation(ItemStack stack){return ItemUseAnimation.NONE;}
    @Override public void onUseTick(Level level,net.minecraft.world.entity.LivingEntity user,ItemStack stack,int remaining){if(user instanceof ServerPlayer p&&(!WorldContentRuntime.get(p.level().getServer()).itemActive(p,stack)||p.containerMenu!=p.inventoryMenu))p.stopUsingItem();}
    @Override public boolean releaseUsing(ItemStack stack,Level level,net.minecraft.world.entity.LivingEntity user,int remaining){
        if(!(user instanceof ServerPlayer p)||p.getItemInHand(p.getUsedItemHand())!=stack||p.containerMenu!=p.inventoryMenu)return false;
        return WorldContentRuntime.get(p.level().getServer()).releaseItem(p,p.getUsedItemHand(),dev.mineagent.runtime.core.objects.ItemThrowMath.usedTicks(remaining));
    }
    @Override public InteractionResult use(Level level,Player player,InteractionHand hand){
        var binding=binding(player.getItemInHand(hand));if(binding==null)return InteractionResult.PASS;
        if(binding.chargeTicks()>0){
            if(level.isClientSide()){player.startUsingItem(hand);return InteractionResult.CONSUME;}
            if(player instanceof ServerPlayer viewer&&dev.mineagent.runtime.neoforge.WorldIdentityRuntime.notifyIfPending(viewer)&&WorldContentRuntime.get(level.getServer()).itemActive(viewer,player.getItemInHand(hand))){player.startUsingItem(hand);return InteractionResult.CONSUME;}
            return InteractionResult.FAIL;
        }
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(!(player instanceof ServerPlayer viewer)||!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.notifyIfPending(viewer))return InteractionResult.PASS;
        return WorldContentRuntime.get(level.getServer()).useItem(viewer,hand)?InteractionResult.SUCCESS:InteractionResult.PASS;
    }
}
