package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Direct logical state avoids re-toggling Minecraft's toggle-sneak/sprint preferences every tick. */
@Mixin(KeyMapping.class)
public interface PlayerControlKeyAccess {
    @Accessor("isDown") void mineagent$bodyDown(boolean down);
    @Accessor("isDown") boolean mineagent$bodyDown();
    @Accessor("clickCount") void mineagent$bodyClicks(int clicks);
    @Accessor("clickCount") int mineagent$bodyClicks();
}
