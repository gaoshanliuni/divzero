package dev.mineagent.runtime.neoforge.client;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.client.screen.WorldReopenRecoveryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
@EventBusSubscriber(modid=MineAgentRuntimeMod.MOD_ID,value=Dist.CLIENT)
public final class WorldReopenRecoveryMenu {
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event){if(event.getScreen() instanceof TitleScreen screen){event.addListener(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("MineAgent 待重开恢复")),b->Minecraft.getInstance().setScreen(new WorldReopenRecoveryScreen(screen))).bounds(Math.max(4,screen.width-172),5,168,20).build());event.addListener(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("MineAgent 本机资源包")),b->Minecraft.getInstance().setScreen(new dev.mineagent.runtime.neoforge.client.screen.LocalResourcePackScreen(screen))).bounds(Math.max(4,screen.width-172),28,168,20).build());}}
}
