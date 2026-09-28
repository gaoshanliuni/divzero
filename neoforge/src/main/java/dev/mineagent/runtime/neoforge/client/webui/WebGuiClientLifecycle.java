package dev.mineagent.runtime.neoforge.client.webui;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Native workspace connection lifecycle. No browser startup or native-core download. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WebGuiClientLifecycle {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){HudPersistenceClient.tick();WebGuiHostAdapter.INSTANCE.tick();WorldUiClient.tick();WebGuiNativeInput.tick();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){HudPersistenceClient.flushWrites();ClientScriptPackages.connectionClosed();WebGuiHostAdapter.INSTANCE.close();}
    private WebGuiClientLifecycle(){}
}
