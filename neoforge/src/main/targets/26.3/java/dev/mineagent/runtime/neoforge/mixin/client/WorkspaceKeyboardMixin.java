package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardHandler.class)
public abstract class WorkspaceKeyboardMixin {
    @Shadow private PreeditEvent lastPreeditEvent;
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void mineagent$workspace(long window,int action,KeyEvent event,CallbackInfo ci){
        if(dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.physicalKey(window,action,event)){ci.cancel();return;}
        if(dev.mineagent.runtime.neoforge.client.webui.KeyboardStartupGate.discard(Minecraft.getInstance().getFramerateLimitTracker())){ci.cancel();return;}
        if(dev.mineagent.runtime.neoforge.client.webui.WebGuiWorkspaceInput.key(window,action,event,lastPreeditEvent!=null&&!lastPreeditEvent.fullText().isEmpty()))ci.cancel();
    }
    // SDL registers input on Window, so KeyboardHandler no longer has setup(Window).
    @Inject(method="<init>",at=@At("RETURN"))
    private void mineagent$bootstrapProbe(Minecraft minecraft,CallbackInfo ci){
        dev.mineagent.runtime.neoforge.client.webui.KeyboardStartupGate.probe((KeyboardHandler)(Object)this,minecraft.getWindow().handle());
    }
}
