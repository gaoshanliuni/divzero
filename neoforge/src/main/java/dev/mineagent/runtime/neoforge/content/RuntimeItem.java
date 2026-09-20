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
    @Override public InteractionResult use(Level level,Player player,InteractionHand hand){
        if(binding(player.getItemInHand(hand))==null)return InteractionResult.PASS;
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(!(player instanceof ServerPlayer viewer)||!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.notifyIfPending(viewer))return InteractionResult.PASS;
        return WorldContentRuntime.get(level.getServer()).useItem(viewer,hand)?InteractionResult.SUCCESS:InteractionResult.PASS;
    }
}
