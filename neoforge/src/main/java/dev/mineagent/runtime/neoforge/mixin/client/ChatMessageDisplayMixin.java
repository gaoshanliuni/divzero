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
    @Override public boolean mineagent$replaceMessage(GuiMessage before,GuiMessage after){
        int index=-1;for(int i=0;i<allMessages.size();i++)if(allMessages.get(i)==before){index=i;break;}if(index<0)return false;
        allMessages.set(index,after);int start=-1,end=-1;for(int i=0;i<trimmedMessages.size();i++)if(trimmedMessages.get(i).parent()==before){if(start<0)start=i;end=i+1;}
        if(start>=0){var mc=net.minecraft.client.Minecraft.getInstance();int width=net.minecraft.util.Mth.floor(net.minecraft.client.gui.components.ChatComponent.getWidth(mc.options.chatWidth().get())/((ChatComponent)(Object)this).getScale());
            var lines=after.splitLines(mc.font,width);var replacement=new java.util.ArrayList<GuiMessage.Line>();for(int i=lines.size()-1;i>=0;i--)replacement.add(new GuiMessage.Line(after,lines.get(i),i==lines.size()-1));
            int delta=replacement.size()-(end-start);trimmedMessages.subList(start,end).clear();trimmedMessages.addAll(start,replacement);
            if(chatScrollbarPos>0&&start<=chatScrollbarPos)chatScrollbarPos=Math.max(start,chatScrollbarPos+delta);scrollChat(0);
        }return true;
    }
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
