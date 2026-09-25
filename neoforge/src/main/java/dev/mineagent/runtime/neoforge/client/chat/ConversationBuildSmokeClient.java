package dev.mineagent.runtime.neoforge.client.chat;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ConversationBuildSmokeServer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;

final class ConversationBuildSmokeClient {
    private static boolean sent,finished;
    private static int ticks;
    static void tick()throws Exception{
        if(finished)return;var mc=Minecraft.getInstance();ticks++;Path root=Files.createDirectories(mc.gameDirectory.toPath().resolve("conversation-build-smoke"));
        try{
            if(!ConversationBuildSmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationBuildSmokeServer.failure);
            if(ticks>13000)throw new IllegalStateException("BUILD_NATIVE_TIMEOUT");
            if(mc.player==null||!ConversationBuildSmokeServer.ready)return;
            if(!sent){sent=true;String prompt=ConversationBuildSmokeServer.web()?"@工具助手 帮我联网查一下暮色森林（Twilight Forest）的巫妖（Lich）怎么打。请读取攻略来源再总结阶段和注意事项，给出处链接；不要修改游戏世界。":ConversationBuildSmokeServer.blueprint()?"@工具助手 读取Create蓝图「"+System.getProperty("mineagent.conversationAgentBlueprintName")+"」，告诉我尺寸、主要材料以及解析限制。只读取，不要建造。":"@工具助手 帮我在旁边空地做一个能持续刷圆石的简单刷石机，可以用我当前权限的游戏命令。不要改动我站的地方。搭好后实际检查产出，告诉我开采点。";Files.writeString(root.resolve("prompt.txt"),prompt);mc.player.connection.sendChat(prompt);}
            if(ConversationBuildSmokeServer.verified){
                var receipts=new ArrayList<JsonElement>();try(var files=Files.list(mc.gameDirectory.toPath().resolve("real-provider-audit"))){for(var f:files.filter(p->p.getFileName().toString().endsWith("-completed.json")).sorted().toList())receipts.add(JsonParser.parseString(Files.readString(f)));}
                if(receipts.isEmpty())throw new IllegalStateException("BUILD_NO_REAL_PROVIDER_RECEIPTS");
                Files.writeString(root.resolve("provider.json"),new Gson().toJson(Map.of("receipts",receipts,"realCalls",receipts.size())));finished=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();
            }
        }catch(Exception e){finished=true;Files.writeString(root.resolve("client-failure.json"),new Gson().toJson(Map.of("error",e.toString(),"sent",sent)));dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();}
    }
}
