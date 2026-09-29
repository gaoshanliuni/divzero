package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.player.*;import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardInput.class)
public abstract class AutonomyKeyboardInputMixin extends ClientInput {
    @Inject(method="tick",at=@At("TAIL"))
    private void divzero$autonomyInput(CallbackInfo ci){if(!dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.active())return;keyPresses=dev.mineagent.runtime.neoforge.client.body.AutonomyVirtualInput.snapshot();moveVector=new Vec2((keyPresses.left()?1:0)-(keyPresses.right()?1:0),(keyPresses.forward()?1:0)-(keyPresses.backward()?1:0)).normalized();}
}
