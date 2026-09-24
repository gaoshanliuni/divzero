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
    @ModifyArg(method="splitLines",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"),index=0)
    private FormattedText mineagent$hover(FormattedText original){return original instanceof Component component?ChatMessageDisplayClient.decorate(component,mineagent$receivedAt):original;}
}
