package dev.mineagent.runtime.neoforge.client.chat;

import dev.mineagent.runtime.neoforge.ui.ConversationRuntimeItemSmokeServer;
import dev.mineagent.runtime.neoforge.client.objects.RuntimeItemRenderer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;

final class ConversationRuntimeItemSmokeClient {
    private static int ticks,wait;private static boolean sent,before,clicked,after,done;private static volatile boolean captureBusy;
    private static String firstHash;
    private static void capture(Path target){captureBusy=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(target);}catch(Exception e){ConversationRuntimeItemSmokeServer.failure=e.toString();}finally{captureBusy=false;}});}
    static void tick()throws Exception{
        if(ConversationRuntimeItemSmokeServer.basketball()){ConversationBasketballSmokeClient.tick();return;}
        if(ConversationRuntimeItemSmokeServer.throwing()){ConversationThrowItemSmokeClient.tick();return;}
        if(done)return;var mc=Minecraft.getInstance();Path root=Files.createDirectories(mc.gameDirectory.toPath().resolve("runtime-item-smoke"));ticks++;
        try{
            if(!ConversationRuntimeItemSmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationRuntimeItemSmokeServer.failure);if(ticks>14000)throw new IllegalStateException("RUNTIME_ITEM_CLIENT_TIMEOUT");if(mc.player==null||!ConversationRuntimeItemSmokeServer.ready)return;
            if(!ConversationRuntimeItemSmokeServer.saved()&&!sent&&++wait>=15){sent=true;String prompt="@工具助手 生成独立HOT物品包并自动启用，给我一枚星辉钥匙：青金色立体模型至少三部位；首次右键把同一栈改为紫色另一模型，改名星辉钥匙·激活，数量保持1。使用次数存在本实例uses状态，instance.create显式初始化字符串0，使用后为字符串1；恢复不清零、不重复发放。代码用item.use/restyle实现，不执行游戏命令，不用旧样例；不要让我再到F2批准。";Files.writeString(root.resolve("prompt.txt"),prompt);mc.player.connection.sendChat(prompt);}
            if(ConversationRuntimeItemSmokeServer.itemReady&&mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen)mc.setScreen(null);
            var binding=dev.mineagent.runtime.neoforge.content.RuntimeItem.binding(mc.player.getMainHandItem());
            if(ConversationRuntimeItemSmokeServer.itemReady&&binding!=null&&!before&&RuntimeItemRenderer.rendered>20){firstHash=binding.assetHash();before=true;capture(root.resolve("before-use.png"));wait=0;}
            if(before&&!captureBusy&&!clicked&&++wait>25){clicked=true;mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);wait=0;}
            if(ConversationRuntimeItemSmokeServer.used&&binding!=null&&!binding.assetHash().equals(firstHash)&&!after&&++wait>35){after=true;capture(root.resolve("after-use.png"));}
            if(after&&!captureBusy){ConversationRuntimeItemSmokeServer.clientCaptured=true;}
            if(ConversationRuntimeItemSmokeServer.verified){Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("renderSubmissions",RuntimeItemRenderer.rendered,"sentNativeUse",clicked,"modelChanged",binding!=null&&!binding.assetHash().equals(firstHash),"systemInputInjected",false)));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();}
        }catch(Exception e){done=true;Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",e.toString(),"renderSubmissions",RuntimeItemRenderer.rendered)));dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();}
    }
}
