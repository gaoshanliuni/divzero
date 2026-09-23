package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.multiplayer.PlayerInfo;import net.minecraft.world.entity.player.PlayerSkin;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerInfo.class)
public abstract class AgentPlayerInfoSkinMixin {
 @Inject(method="getSkin",at=@At("HEAD"),cancellable=true) private void mineagent$customSkin(CallbackInfoReturnable<PlayerSkin> c){var id=((PlayerInfo)(Object)this).getProfile().id();var skin=dev.mineagent.runtime.neoforge.client.AgentSkinClient.skin(id);if(skin!=null)c.setReturnValue(skin);}
}
