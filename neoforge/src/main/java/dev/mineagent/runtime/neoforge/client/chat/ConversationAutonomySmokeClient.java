package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.ConversationAutonomySmokeServer;
import dev.mineagent.runtime.neoforge.client.body.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import java.util.*;
import java.nio.file.*;
final class ConversationAutonomySmokeClient {
    private static boolean sent,focused,approved,captured,done,stopped,pauseTested;private static int ticks,pauseTicks;private static volatile boolean busy;
    private static Path root()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("autonomy-smoke"));}
    static void tick()throws Exception{if(done)return;var mc=Minecraft.getInstance();ticks++;try{if(!ConversationAutonomySmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationAutonomySmokeServer.failure);if(ticks>19000)throw new IllegalStateException("AUTONOMY_CLIENT_TIMEOUT");if(mc.player==null||!ConversationAutonomySmokeServer.ready)return;
        if(!sent){org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());sent=true;mc.player.connection.sendChat("@工具助手 接管我的身体，走到附近金块地面上，遇到新障碍就绕开。到达后把我的视角转向旁边石英方块，保持接管待命，直到我按Esc主动停止。不要用命令或传送，不要每次规划都再让我确认。");}
        if(mc.screen instanceof AutonomousBodyClient.StartScreen)throw new IllegalStateException("UNEXPECTED_TAKEOVER_CONFIRMATION");
        if(AutonomousBodyClient.active()&&ConversationAutonomySmokeServer.blocked&&!captured&&((Number)AutonomousBodyClient.observe().get("reroutes")).intValue()>1){captured=true;busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("live-left-hud.png"));}catch(Exception e){ConversationAutonomySmokeServer.failure=e.toString();}finally{busy=false;}});}
        if(ConversationAutonomySmokeServer.arrived&&AutonomousBodyClient.active()&&!pauseTested){pauseTested=true;mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("",false));pauseTicks=ticks;}
        if(pauseTested&&pauseTicks>0&&ticks-pauseTicks>20){if(!AutonomousBodyClient.active())throw new IllegalStateException("AUTONOMY_CHAT_ENDED_SESSION");mc.setScreen(null);pauseTicks=-1;}
        if(pauseTested&&pauseTicks==-1&&ticks%40==0&&!stopped&&AutonomousBodyClient.observe().get("state").equals("IDLE")){if(!AutonomousBodyClient.active()||AutonomousBodyClient.localApprovals!=0||AutonomousBodyClient.hudDraws<50)throw new IllegalStateException("AUTONOMY_APPROVAL_OR_HUD");if(!PlayerBodyControlClient.physicalKey(mc.getWindow().handle(),1,new KeyEvent(87,0,0))||!AutonomousBodyClient.active())throw new IllegalStateException("AUTONOMY_ORDINARY_KEY_STOPPED");PlayerBodyControlClient.physicalKey(mc.getWindow().handle(),1,new KeyEvent(256,0,0));if(AutonomousBodyClient.active())throw new IllegalStateException("AUTONOMY_ESCAPE_FAILED");stopped=true;}
        if(ConversationAutonomySmokeServer.verified&&stopped&&!busy){Files.writeString(root().resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("status","CONTINUOUS_NATIVE_CLIENT_VERIFIED","localApprovals",AutonomousBodyClient.localApprovals,"hudDraws",AutonomousBodyClient.hudDraws,"pauseKeptSession",pauseTested,"systemInputInjected",false,"keys",PlayerBodyControlClient.observe())));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
    }catch(Exception e){done=true;AutonomousBodyClient.stop("SMOKE_END");Files.writeString(root().resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",e.toString(),"observed",AutonomousBodyClient.observe())));mc.stop();}}
}
