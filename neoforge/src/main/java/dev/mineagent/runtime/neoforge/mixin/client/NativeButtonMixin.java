package dev.mineagent.runtime.neoforge.mixin.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import dev.mineagent.runtime.neoforge.client.nativeui.NativeButtonFeedback;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only DivZero-owned trees change behavior; other mods retain their LDLib2 button policy. */
@Mixin(value=Button.class,remap=false)
public abstract class NativeButtonMixin implements NativeButtonFeedback.Access {
    @Shadow private UIEventListener onClick;
    @Shadow protected abstract void setButtonState(Button.State state);
    @Unique private NativeButtonFeedback divzero$gesture;
    @Override public NativeButtonFeedback divzero$feedback(){if(divzero$gesture==null)divzero$gesture=new NativeButtonFeedback((Button)(Object)this);return divzero$gesture;}
    @Override public UIEventListener divzero$clickHandler(){return onClick;}
    @Override public void divzero$buttonState(Button.State state){setButtonState(state);}
    @Inject(method="onMouseDown",at=@At("HEAD"),cancellable=true)
    private void divzero$press(UIEvent event,CallbackInfo ci){if(NativeButtonFeedback.owns((Button)(Object)this)){divzero$feedback().mouseDown(event);ci.cancel();}}
    @Inject(method="onMouseUp",at=@At("HEAD"),cancellable=true)
    private void divzero$release(UIEvent event,CallbackInfo ci){if(NativeButtonFeedback.owns((Button)(Object)this)){divzero$feedback().mouseUp(event);ci.cancel();}}
    @Inject(method="onMouseEnter",at=@At("HEAD"),cancellable=true)
    private void divzero$enter(UIEvent event,CallbackInfo ci){if(NativeButtonFeedback.owns((Button)(Object)this)){divzero$feedback().mouseEnter(event);ci.cancel();}}
    @Inject(method="onMouseLeave",at=@At("HEAD"),cancellable=true)
    private void divzero$leave(UIEvent event,CallbackInfo ci){if(NativeButtonFeedback.owns((Button)(Object)this)){divzero$feedback().mouseLeave(event);ci.cancel();}}
}
