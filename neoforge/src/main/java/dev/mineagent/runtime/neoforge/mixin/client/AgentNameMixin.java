package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Native entity data carries the display name; the stable network profile remains internal. */
@Mixin(Player.class)
public abstract class AgentNameMixin {
    @Inject(method={"getName","getDisplayName"},at=@At("HEAD"),cancellable=true)
    private void mineagent$name(CallbackInfoReturnable<Component> ci){
        var p=(Player)(Object)this;
        if(p.getGameProfile().name().startsWith("MA_")&&p.getCustomName()!=null)ci.setReturnValue(p.getCustomName());
    }
}
