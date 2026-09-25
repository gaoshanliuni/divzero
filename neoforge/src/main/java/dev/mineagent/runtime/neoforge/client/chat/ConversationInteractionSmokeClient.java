package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.ConversationInteractionSmokeServer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;

final class ConversationInteractionSmokeClient {
    private static boolean sent,finished,sawMenu,sawScreen;private static int ticks,readyTicks;
    static void tick()throws Exception{
        if(finished)return;var mc=Minecraft.getInstance();ticks++;var root=Files.createDirectories(mc.gameDirectory.toPath().resolve("conversation-interaction-smoke"));
        try{
            if(!ConversationInteractionSmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationInteractionSmokeServer.failure);
            if(ticks>13000)throw new IllegalStateException("INTERACTION_CLIENT_TIMEOUT");if(mc.player==null||!ConversationInteractionSmokeServer.ready)return;
            if(mc.player.containerMenu!=mc.player.inventoryMenu)sawMenu=true;if(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)sawScreen=true;
            if(!sent&&++readyTicks>=12){sent=true;String prompt="@工具助手 把我面前箱子里的钻石全部收进我的背包，别拿铁锭，然后关上箱子，再打开旁边的拉杆。请真实交互，不用任何游戏指令，不要生成或删除物品。";Files.writeString(root.resolve("prompt.txt"),prompt);mc.player.connection.sendChat(prompt);}
            if(ConversationInteractionSmokeServer.verified){
                int count=0;for(int i=0;i<mc.player.getInventory().getContainerSize();i++){var stack=mc.player.getInventory().getItem(i);if(stack.is(net.minecraft.world.item.Items.DIAMOND))count+=stack.getCount();}
                if(count!=3||mc.player.containerMenu!=mc.player.inventoryMenu)return;if(!sawMenu||!sawScreen)throw new IllegalStateException("INTERACTION_NATIVE_MENU_NOT_SEEN_BY_CLIENT");
                Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("nativeMenuObserved",sawMenu,"nativeScreenObserved",sawScreen,"finalMenuClosed",true,"clientDiamonds",count,"systemInputInjected",false)));
                finished=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();
            }
        }catch(Exception failure){finished=true;Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",failure.toString(),"nativeMenuObserved",sawMenu,"nativeScreenObserved",sawScreen)));dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();}
    }
}
