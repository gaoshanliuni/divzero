package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.CreatureEventsSmokeServer;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen;
import net.minecraft.client.Minecraft;
import java.nio.file.*;import java.util.*;
public final class CreatureEventsSmokeClient {
 private static int ticks,wait;private static boolean sent,done;
 public static void tick()throws Exception{if(done)return;var mc=Minecraft.getInstance();ticks++;var root=Files.createDirectories(mc.gameDirectory.toPath().resolve("creature-events-smoke"));try{
  if(!CreatureEventsSmokeServer.failure.isEmpty())throw new IllegalStateException(CreatureEventsSmokeServer.failure);if(ticks>18000)throw new IllegalStateException("EVENTS_CLIENT_TIMEOUT");if(mc.player==null||!CreatureEventsSmokeServer.ready)return;
  if(!sent){String fp=PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");if(fp.isEmpty()||new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fp)!=dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){if(mc.screen instanceof ControlCenterScreen s)for(var child:List.copyOf(s.children()))if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().contains("信任此服务器"))b.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));return;}if(++wait==1)mc.player.connection.sendCommand("ai accept");if(wait<40)return;mc.setScreen(null);if(!CreatureEventsSmokeServer.saved())mc.player.connection.sendChat("@事件助手 定义两个全新生物，暂不生成实体：1友好礼物精灵，靠近3格一次送2苹果、给予5秒速度二级、播放经验球拾取音，离开后100tick冷却可再触发。2中立爆爆球，靠近3格启动40tick引信、离开取消，到时威力2爆炸消失且不破坏方块；冷却100tick。都用简单球形模型。先读能力契约，实际定义并读回，不用聊天冒充。");sent=true;CreatureEventsSmokeServer.clientReady=true;}
  if(CreatureEventsSmokeServer.complete){Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("status","CREATURE_EVENTS_NATIVE_CLIENT_COMPLETE","osInputInjected",false)));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
 }catch(Exception e){done=true;Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",e.toString(),"stage",CreatureEventsSmokeServer.stage)));mc.stop();}}
}
