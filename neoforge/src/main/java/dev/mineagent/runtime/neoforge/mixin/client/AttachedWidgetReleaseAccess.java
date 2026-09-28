package dev.mineagent.runtime.neoforge.mixin.client;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUIWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** A consumed NeoForge Pre release does not advance the vanilla container's LDLib2 duplicate guard. */
@Mixin(value=ModularUIWidget.class,remap=false)
public interface AttachedWidgetReleaseAccess {
    @Accessor("lastMouseReleasedMark") void divzero$releaseMark(int previous);
}
