package dev.mineagent.runtime.neoforge.chat;
import net.minecraft.network.chat.Component;import net.minecraft.network.chat.MutableComponent;
/** Explicit name span, preserving the same visible text and separate body/button styles. */
public final class AiChatMessages {
    public static final String NAME_KEY="mineagent.chat.agent_name";
    public static MutableComponent name(String name){return Component.translatableWithFallback(NAME_KEY,"[%s]",Component.literal(name));}
    public static MutableComponent line(String name,String text){return Component.empty().append(name(name)).append(Component.literal(text));}
    private AiChatMessages(){}
}
