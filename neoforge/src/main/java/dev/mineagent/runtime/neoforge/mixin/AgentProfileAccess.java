package dev.mineagent.runtime.neoforge.mixin;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Player.class)
public interface AgentProfileAccess {
 @Mutable @Accessor("gameProfile") void divzero$profile(GameProfile profile);
}
