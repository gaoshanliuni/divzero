package dev.mineagent.runtime.neoforge.mixin.client;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(value=TextField.class,remap=false)
public interface LdTextFieldEditAccess {
 @Invoker("getClipboardSelectionText") String divzero$selection();
}
