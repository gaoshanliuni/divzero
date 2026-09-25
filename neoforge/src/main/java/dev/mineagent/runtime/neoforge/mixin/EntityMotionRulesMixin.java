package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.Entity;import net.minecraft.world.phys.Vec3;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
@Mixin(Entity.class)
public abstract class EntityMotionRulesMixin {
 @ModifyVariable(method="move",at=@At("HEAD"),argsOnly=true) private Vec3 mineagent$motion(Vec3 movement){return dev.mineagent.runtime.neoforge.ui.ServerEntityInterop.motion((Entity)(Object)this,movement);}
}
