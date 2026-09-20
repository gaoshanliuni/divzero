package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ChatScreen.class)
public interface ChatSuggestionsAccess { @Accessor("commandSuggestions") CommandSuggestions mineagent$suggestions(); }
