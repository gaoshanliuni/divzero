package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(MouseHandler.class)
public interface WorkspaceMouseAccess {
    @Accessor("isLeftPressed") void mineagent$left(boolean value);
    @Accessor("isMiddlePressed") void mineagent$middle(boolean value);
    @Accessor("isRightPressed") void mineagent$right(boolean value);
    @Accessor("activeButton") void mineagent$activeButton(MouseButtonInfo value);
    // SDL no longer stores GLFW synthetic-right-click state.
    default void mineagent$fakeRight(int value) {}
}
