package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.Minecraft;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;
/** Vanilla physical mouse state must not abort or duplicate the separate native autonomy action. */
@Mixin(Minecraft.class)
public abstract class AutonomyGameInputMixin {
    @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
    private void divzero$continueAttack(boolean held,CallbackInfo ci){if(dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.active())ci.cancel();}
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void divzero$use(CallbackInfo ci){if(dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.active())ci.cancel();}
    @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
    private void divzero$attack(CallbackInfoReturnable<Boolean> ci){if(dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.active())ci.setReturnValue(false);}
}
