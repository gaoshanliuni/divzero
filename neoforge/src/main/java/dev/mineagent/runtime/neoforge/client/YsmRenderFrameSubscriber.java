package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

@EventBusSubscriber(modid = MineAgentRuntimeMod.MOD_ID, value = Dist.CLIENT)
public final class YsmRenderFrameSubscriber {
    private YsmRenderFrameSubscriber() {
    }

    @SubscribeEvent
    static void beforeRenderFrame(RenderFrameEvent.Pre event) {
        YsmRenderObserver.beginFrame(System.nanoTime());
    }
}
