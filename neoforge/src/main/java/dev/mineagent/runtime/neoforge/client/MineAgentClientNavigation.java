package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.api.config.PanelSection;
import dev.mineagent.runtime.neoforge.client.nativeui.NativeWorkspaceScreen;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;

public final class MineAgentClientNavigation {
    private MineAgentClientNavigation() {
    }

    public static void open(MineAgentPayloads.OpenPanel payload) {
        if(!MineAgentClientTrustPrompt.enabled()){Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen("",false));MineAgentClientTrustPrompt.showChoice(true);return;}
        if(payload.section().equals("WORKSPACE")){NativeWorkspaceScreen.open();return;}
        PanelSection section;
        try {
            section = PanelSection.valueOf(payload.section());
        } catch (IllegalArgumentException invalid) {
            section = PanelSection.OVERVIEW;
        }
        NativeWorkspaceScreen.openSection(section);
    }
}
