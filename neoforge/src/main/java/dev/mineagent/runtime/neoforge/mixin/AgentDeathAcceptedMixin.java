package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TAIL excludes NeoForge's cancelled-death early return and schedules only after native loot. */
@Mixin(ServerPlayer.class)
public abstract class AgentDeathAcceptedMixin {
    @Inject(method="die",at=@At("TAIL"),require=1)
    private void mineagent$acceptedDeath(DamageSource source,CallbackInfo ci){if((Object)this instanceof MineAgentPlayer body)body.nativeDeathAccepted();}
}
