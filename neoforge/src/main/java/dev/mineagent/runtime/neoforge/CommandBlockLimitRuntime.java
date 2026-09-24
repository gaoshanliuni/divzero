package dev.mineagent.runtime.neoforge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.minecraft.world.level.gamerules.GameRules;
/** Modern name of commandModificationBlockLimit; applied once per server start, not each tick. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class CommandBlockLimitRuntime {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        for (var level : event.getServer().getAllLevels())
            level.getGameRules().set(GameRules.MAX_BLOCK_MODIFICATIONS, Integer.MAX_VALUE, event.getServer());
    }
    private CommandBlockLimitRuntime() {}
}
