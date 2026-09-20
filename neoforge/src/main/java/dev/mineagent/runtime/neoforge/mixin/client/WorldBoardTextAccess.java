package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Client NBT loading deliberately clears text in 26.1.2. Only inject already-authorized literal components. */
@Mixin(Display.TextDisplay.class)
public interface WorldBoardTextAccess {
    @Invoker("setText") void mineagent$setText(Component text);
}
