package dev.mineagent.runtime.neoforge.mixin.client;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.Accessor;import net.minecraft.client.gui.components.ChatComponent;import net.minecraft.client.multiplayer.chat.GuiMessage;import java.util.List;
@Mixin(ChatComponent.class)
public interface ChatHistoryAccess {@Accessor("allMessages") List<GuiMessage> mineagent$messages();}
