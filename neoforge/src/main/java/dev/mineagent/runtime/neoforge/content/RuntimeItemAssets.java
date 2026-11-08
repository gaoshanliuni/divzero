package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.core.objects.RuntimeItemBinding;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A render request must reference an actually visible stack or the viewer's real container slots. */
public final class RuntimeItemAssets {
    public static RuntimeItemBinding visible(Entity entity,Player viewer,String asset){
        if(entity==null)return null;
        if(entity instanceof net.minecraft.world.entity.item.ItemEntity item)return matching(item.getItem(),asset);
        if(entity instanceof RuntimeThrownItemEntity item)return matching(item.item(),asset);
        if(entity instanceof LivingEntity living)for(var slot:EquipmentSlot.values()){var found=matching(living.getItemBySlot(slot),asset);if(found!=null)return found;}
        if(entity==viewer){for(var slot:viewer.containerMenu.slots){var found=matching(slot.getItem(),asset);if(found!=null)return found;}
            var found=matching(viewer.containerMenu.getCarried(),asset);if(found!=null)return found;
            for(int i=0;i<viewer.getInventory().getContainerSize();i++){found=matching(viewer.getInventory().getItem(i),asset);if(found!=null)return found;}
        }
        return null;
    }
    private static RuntimeItemBinding matching(ItemStack stack,String asset){var binding=RuntimeItem.binding(stack);return binding!=null&&binding.assetHash().equals(asset)?binding:null;}
    private RuntimeItemAssets(){}
}
