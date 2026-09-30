package dev.mineagent.runtime.neoforge.mixin;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.AreaEffectCloud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(AreaEffectCloud.class)
public interface CombatCloudAccess {
    @Accessor("victims") Map<Entity,Integer> divzero$victims();
}
