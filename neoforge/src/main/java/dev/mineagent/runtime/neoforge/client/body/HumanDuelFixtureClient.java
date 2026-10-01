package dev.mineagent.runtime.neoforge.client.body;

/** Automated lifecycle fixture only. Never enabled for the user's human practice session. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class HumanDuelFixtureClient {
    private static int ticks;
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.humanDuelFixture")||++ticks%20!=0)return;
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("human-duel-fixture.json")))mc.stop();
    }
    private HumanDuelFixtureClient(){}
}
