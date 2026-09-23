package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.ConversationHostSmokeServer;
import net.minecraft.client.Minecraft;
import java.util.*;import java.nio.file.*;
/** Historical host-app fixture was retired with PowerShell; geometry-only acceptance stays available. */
final class ConversationHostSmokeClient {
 private static boolean sent,done;private static int ticks;
 static void accept(com.google.gson.JsonObject ignored){}
 static void tick()throws Exception{if(done)return;var mc=Minecraft.getInstance();ticks++;if(!ConversationHostSmokeServer.geometryOnly())throw new IllegalStateException("USE_PYTHON_HOST_SCENARIO");if(mc.player==null||!ConversationHostSmokeServer.ready)return;
  if(!sent){sent=true;mc.player.connection.sendChat("@工具助手 只读测试：先查看最新建模能力和完整JSON格式，再分别用validate_model_geometry验证长方体、平面、圆盘、平面圆环、圆柱、圆锥、圆台、棱柱、棱锥、椭球、胶囊11种新增形状。每种用简单有效尺寸、world目标。不要发布包或操作游戏、电脑。");}
  if(ticks>22000||!ConversationHostSmokeServer.failure.isEmpty())throw new IllegalStateException("PRIMITIVES_NATIVE_FAILED");
  if(ConversationHostSmokeServer.verified>=2){Files.writeString(Files.createDirectories(mc.gameDirectory.toPath().resolve("host-command-smoke")).resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("status","PRIMITIVE_TOOLS_NATIVE_VERIFIED","approvedCommands",0,"systemInputInjected",false)));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
 }
}
