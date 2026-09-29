package dev.mineagent.runtime.neoforge.mixin.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Root-owned popups must sit above the workspace windows that opened them. */
@Mixin(value=Selector.class,remap=false)
public abstract class NativeSelectorMixin {
    @Inject(method="show",at=@At("HEAD"))
    private void divzero$popupLayer(CallbackInfo ci){
        var selector=(Selector<?>)(Object)this;
        if(!NativeButtonFeedback.owns(selector)||selector.getModularUI()==null||selector.isOpen())return;
        var root=selector.getModularUI().ui.rootElement;
        int layer=2001;for(var child:root.getChildren())layer=Math.max(layer,child.getStyle().zIndex()+1);
        selector.dialog.getStyle().zIndex(layer);
        // Initialize option buttons before the UI focus dispatcher handles the first press.
        selector.dialog.addClass(NativeButtonFeedback.ROOT_CLASS);
        NativeUiTheme.controls(selector.dialog);
    }
}
