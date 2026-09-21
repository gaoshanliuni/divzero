package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.ConversationDiamondSmokeServer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
final class ConversationDiamondSmokeClient {
    private static int ticks;private static boolean sent,done;
    static void tick()throws Exception{
        if(done)return;var mc=Minecraft.getInstance();ticks++;
        try{if(!ConversationDiamondSmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationDiamondSmokeServer.failure);if(ticks>14000)throw new IllegalStateException("DIAMOND_TIMEOUT");if(mc.player==null||!ConversationDiamondSmokeServer.ready)return;
            if(!sent){sent=true;mc.player.connection.sendChat("@工具助手 帮我找附近的钻石矿，先查小范围，没有就逐步扩大，告诉我实际找到的坐标；不要改方块、传送或给我物品。");}
            if(ConversationDiamondSmokeServer.verified){done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
        }catch(Exception e){done=true;Files.writeString(Files.createDirectories(mc.gameDirectory.toPath().resolve("diamond-smoke")).resolve("client-failure.txt"),e.toString());mc.stop();}
    }
}
