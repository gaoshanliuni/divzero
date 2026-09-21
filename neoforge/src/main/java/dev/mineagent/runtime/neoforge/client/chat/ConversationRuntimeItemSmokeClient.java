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
            if(!ConversationRuntimeItemSmokeServer.saved()&&!sent&&++wait>=15){sent=true;String prompt="@工具助手 请生成独立的HOT物品内容包：给我一枚名叫星辉钥匙的新物品，自己设计青色与金色的立体钥匙模型，至少由三块不同部位组成。第一次右键使用后，实际把这同一件物品改成紫色的另一套模型，并改名为星辉钥匙·激活，数量始终是1。把使用次数保存在本实例uses状态中，从0变为1。用生成的包脚本实现，不要用原版物品改名或旧篮球示例，不执行游戏命令。先生成源码和模型，等我审查并明确启用后，在首次创建实例时自动给我这一枚钥匙；不要再等待额外的发放接口，恢复时不要重复发放。";Files.writeString(root.resolve("prompt.txt"),prompt);mc.player.connection.sendChat(prompt);}
            if(ConversationRuntimeItemSmokeServer.itemReady&&mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen)mc.setScreen(null);
            var binding=dev.mineagent.runtime.neoforge.content.RuntimeItem.binding(mc.player.getMainHandItem());
            if(ConversationRuntimeItemSmokeServer.itemReady&&binding!=null&&!before&&RuntimeItemRenderer.rendered>20){firstHash=binding.assetHash();before=true;capture(root.resolve("before-use.png"));wait=0;}
            if(before&&!captureBusy&&!clicked&&++wait>25){clicked=true;mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);wait=0;}
            if(ConversationRuntimeItemSmokeServer.used&&binding!=null&&!binding.assetHash().equals(firstHash)&&!after&&++wait>35){after=true;capture(root.resolve("after-use.png"));}
            if(after&&!captureBusy){ConversationRuntimeItemSmokeServer.clientCaptured=true;}
            if(ConversationRuntimeItemSmokeServer.verified){Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("renderSubmissions",RuntimeItemRenderer.rendered,"sentNativeUse",clicked,"modelChanged",binding!=null&&!binding.assetHash().equals(firstHash),"systemInputInjected",false)));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
        }catch(Exception e){done=true;Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",e.toString(),"renderSubmissions",RuntimeItemRenderer.rendered)));mc.stop();}
    }
}
