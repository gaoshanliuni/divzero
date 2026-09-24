package dev.mineagent.runtime.neoforge.mixin.client;
import dev.mineagent.runtime.neoforge.client.chat.*;
import net.minecraft.client.gui.components.ChatComponent;import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
@Mixin(ChatComponent.class)
public abstract class ChatMessageDisplayMixin implements ChatDisplayAccess {
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow protected abstract void refreshTrimmedMessages();
    @Shadow private int chatScrollbarPos;
    @Shadow public abstract void scrollChat(int amount);
    @ModifyConstant(method="addMessageToQueue",constant=@Constant(intValue=100)) private int mineagent$messageLimit(int old){return ChatMessageDisplayClient.limit();}
    // Wrapped lines are retained with their parent, not counted as separate messages.
    @ModifyConstant(method="addMessageToDisplayQueue",constant=@Constant(intValue=100)) private int mineagent$lineLimit(int old){return Integer.MAX_VALUE;}
    @Redirect(method="addMessageToQueue",at=@At(value="INVOKE",target="Ljava/util/List;removeLast()Ljava/lang/Object;"))
    private Object mineagent$removeOldMessage(List<GuiMessage> messages){var removed=messages.removeLast();while(!trimmedMessages.isEmpty()&&trimmedMessages.getLast().parent()==removed)trimmedMessages.removeLast();return removed;}
    @Override public void mineagent$refreshMessageDisplay(){int limit=ChatMessageDisplayClient.limit();if(allMessages.size()>limit)allMessages.subList(limit,allMessages.size()).clear();int scroll=chatScrollbarPos;chatScrollbarPos=0;refreshTrimmedMessages();chatScrollbarPos=scroll;scrollChat(0);}
    @Inject(method="addMessageToQueue",at=@At("RETURN")) private void mineagent$clampScroll(net.minecraft.client.multiplayer.chat.GuiMessage message,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){scrollChat(0);}
    @Inject(method="restoreState",at=@At("RETURN")) private void mineagent$restoreLimit(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(allMessages.size()>ChatMessageDisplayClient.limit())mineagent$refreshMessageDisplay();}
    @Inject(method="createDeletedMarker",at=@At("RETURN")) private static void mineagent$keepReceiptTime(GuiMessage message,CallbackInfoReturnable<GuiMessage> cir){if((Object)message instanceof ChatMessageClock old&&(Object)cir.getReturnValue() instanceof ChatMessageClock replacement)replacement.mineagent$receivedAt(old.mineagent$receivedAt());}
}
