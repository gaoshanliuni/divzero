package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.*;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Observe the result of vanilla/mod filtering; never turn a rejected player chat into a system message. */
@Mixin(ChatListener.class)
public abstract class AiPlayerChatDeliveryMixin {
 @Inject(method="showMessageToPlayer",at=@At("RETURN"))
 private void divzero$delivered(ChatType.Bound type,PlayerChatMessage message,Component decorated,GameProfile profile,boolean secure,Instant received,CallbackInfoReturnable<Boolean> result){
  dev.mineagent.runtime.neoforge.client.chat.NativeStreamingChat.nativeMessage(message,profile,result.getReturnValue());
 }
}
