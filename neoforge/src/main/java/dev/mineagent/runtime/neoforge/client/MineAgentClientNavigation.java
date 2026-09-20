package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.api.config.PanelSection;
import dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;

public final class MineAgentClientNavigation {
    private MineAgentClientNavigation() {
    }

    public static void open(MineAgentPayloads.OpenPanel payload) {
        PanelSection section;
        try {
            section = PanelSection.valueOf(payload.section());
        } catch (IllegalArgumentException invalid) {
            section = PanelSection.OVERVIEW;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(ControlCenterScreen.create(minecraft.screen, section));
    }
}
