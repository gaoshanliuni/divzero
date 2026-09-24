package dev.mineagent.runtime.neoforge.client.basketball;

import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class MineAgentBasketballHud {
    private MineAgentBasketballHud() {
    }

    public static void accept(MineAgentPayloads.BasketballScore payload) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal(
                    "+" + payload.points() + dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(" 分  |  总分 ") + payload.totalScore()));
            if (Boolean.getBoolean("mineagent.smokeTest")) {
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                        "MINEAGENT_SMOKE_BASKETBALL_HUD_OK points={} total={}",
                        payload.points(), payload.totalScore());
            }
        }
    }
}
