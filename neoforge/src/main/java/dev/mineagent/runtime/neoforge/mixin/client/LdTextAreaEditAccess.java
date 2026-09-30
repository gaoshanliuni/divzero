package dev.mineagent.runtime.neoforge.mixin.client;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(value=TextArea.class,remap=false)
public interface LdTextAreaEditAccess {
 @Invoker("getClipboardSelectionText") String divzero$selection();
 @Invoker("selectAll") void divzero$selectAll();
 @Invoker("insertText") void divzero$insert(String text);
}
