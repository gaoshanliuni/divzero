package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.suggestion.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.CompletableFuture;

/** Reuses vanilla SuggestionsList for Tab, arrows, mouse selection, narration and replacement range. */
@Mixin(CommandSuggestions.class)
public abstract class AgentMentionSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Shadow @Final private Screen screen;
    @Shadow @Final private boolean commandsOnly;
    @Shadow private boolean keepSuggestions;
    @Shadow private boolean messagesAllowed;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow public abstract void showSuggestions(boolean immediateNarration);
    @Inject(method="updateCommandInfo",at=@At("RETURN"))
    private void mineagent$mentions(CallbackInfo ci){
        if(commandsOnly||keepSuggestions||!messagesAllowed||!(screen instanceof ChatScreen))return;
        var match=dev.mineagent.runtime.core.interaction.AgentMention.complete(input.getValue(),input.getCursorPosition(),dev.mineagent.runtime.neoforge.client.chat.NativeAgentChat.names());if(match.isEmpty())return;
        var selection=match.orElseThrow();var builder=new SuggestionsBuilder(input.getValue().substring(0,input.getCursorPosition()),selection.start());for(String value:selection.values())builder.suggest(value,Component.literal("AI · 私密对话"));pendingSuggestions=builder.buildFuture();showSuggestions(false);
    }
}
