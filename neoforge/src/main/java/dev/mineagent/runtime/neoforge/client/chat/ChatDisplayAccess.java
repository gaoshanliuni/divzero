package dev.mineagent.runtime.neoforge.client.chat;
public interface ChatDisplayAccess {
    void mineagent$refreshMessageDisplay();
    boolean mineagent$replaceMessage(net.minecraft.client.multiplayer.chat.GuiMessage before,net.minecraft.client.multiplayer.chat.GuiMessage after);
}
