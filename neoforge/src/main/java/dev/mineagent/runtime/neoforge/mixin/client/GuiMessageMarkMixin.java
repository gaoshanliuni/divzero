package dev.mineagent.runtime.neoforge.mixin.client;
import dev.mineagent.runtime.neoforge.client.chat.*;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.*;
import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GuiMessage.class)
public abstract class GuiMessageMarkMixin implements ChatMessageClock {
    @Unique private long mineagent$receivedAt;
    @Inject(method="<init>",at=@At("RETURN")) private void mineagent$receive(CallbackInfo ci){mineagent$receivedAt=System.currentTimeMillis();}
    @Override public long mineagent$receivedAt(){return mineagent$receivedAt;}
    @Override public void mineagent$receivedAt(long value){mineagent$receivedAt=value;}
    @Inject(method="splitLines",at=@At("HEAD"),cancellable=true)
    private void mineagent$streamLines(net.minecraft.client.gui.Font font,int width,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.List<net.minecraft.util.FormattedCharSequence>> ci){var lines=NativeStreamingChat.lines((GuiMessage)(Object)this,font,width);if(lines!=null)ci.setReturnValue(lines);}
    @ModifyArg(method="splitLines",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"),index=0)
    private FormattedText mineagent$hover(FormattedText original){return original instanceof Component component?ChatMessageDisplayClient.decorate(component,mineagent$receivedAt):original;}
}
