package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.WorldGeometrySmokeServer;
import dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;
public final class WorldGeometrySmokeClient {
    private static int ticks,wait;private static boolean sent,done,shot;
    public static void tick()throws Exception{if(done)return;var mc=Minecraft.getInstance();ticks++;Path root=Files.createDirectories(mc.gameDirectory.toPath().resolve("world-geometry-smoke"));try{
        if(!WorldGeometrySmokeServer.failure.isEmpty())throw new IllegalStateException(WorldGeometrySmokeServer.failure);if(ticks>54000)throw new IllegalStateException("GEOMETRY_NATIVE_TIMEOUT");if(mc.player==null||!WorldGeometrySmokeServer.ready)return;
        if(!WorldGeometrySmokeServer.clientReady){String fp=PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");if(fp.isEmpty()||new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fp)!=dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){if(mc.screen instanceof ControlCenterScreen screen)for(var child:List.copyOf(screen.children()))if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().contains("信任此服务器"))b.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));return;}if(++wait==1)mc.player.connection.sendCommand("ai accept");if(wait<40)return;WorldGeometrySmokeServer.clientReady=true;mc.setScreen(null);}
        if(!sent&&WorldGeometrySmokeServer.preflightDone()&&!WorldGeometrySmokeServer.zero()){
            String prompt="@建筑师 在x0..74,z0..19,y111..120展区，用世界几何工具依次做14件小样：折线、斜平面、四面墙、壳盒、L形拉伸、坡屋顶、Bezier拱、扭曲曲面、空心柱、圆顶、完整朝向楼梯(rotate+mirror)、柱阵列、沿折线重复柱(混材规则)、仅将第一件石材替换为金块(replace)。每件长宽高各≤7格，分两排错开勿碰玩家；先读契约。每件单独plan→apply→inspect计划，再下一件；14件全部完成才回复，不用命令/包。";
            Files.writeString(root.resolve("prompt.txt"),prompt);mc.player.connection.sendChat(prompt);sent=true;
        }
        if(WorldGeometrySmokeServer.complete&&!shot){shot=true;wait=0;mc.setScreen(null);mc.player.setYRot(-90);mc.player.setXRot(-8);}
        if(shot&&++wait>40){done=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),img->{try(img){img.writeToFile(root.resolve("geometry-native.png"));Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("status","NATIVE_GEOMETRY_CLIENT_COMPLETE","sentRealChat",sent,"osInputInjected",false)));dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");}catch(Exception e){try{Files.writeString(root.resolve("screenshot-failure.txt"),e.toString());}catch(Exception ignored){}}finally{mc.stop();}});}
    }catch(Exception e){done=true;Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",e.toString(),"sent",sent)));mc.stop();}}
    private WorldGeometrySmokeClient(){}
}
