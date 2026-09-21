package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(AbstractClientPlayer.class)
public abstract class AgentSkinMixin {
    @Inject(method="getSkin",at=@At("HEAD"),cancellable=true)
    private void mineagent$skin(CallbackInfoReturnable<PlayerSkin> callback){var skin=dev.mineagent.runtime.neoforge.client.AgentSkinClient.skin(((AbstractClientPlayer)(Object)this).getUUID());if(skin!=null)callback.setReturnValue(skin);}
}
