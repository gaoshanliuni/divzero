package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.Gson;
import dev.mineagent.runtime.neoforge.task.AudienceSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Generic task UI alongside Native addressing tests. No system input and no simulated delivery/RENDERED receipt. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class AudienceSmokeClient {
    private static int ticks,phase,after;private static boolean backup,trusted,pressed,busy;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!AudienceSmokeServer.enabled()||phase==99)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        Path root=mc.gameDirectory.toPath().resolve("audience-evidence");Files.createDirectories(root);
        if(phase==90){if(ticks>=after){phase=99;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_AUDIENCE_{}_OK",AudienceSmokeServer.stage().toUpperCase(Locale.ROOT));mc.stop();}return;}
        if(phase==98){if(ticks>=after){phase=99;mc.stop();}return;}
        if(AudienceSmokeServer.failure!=null||ticks>2400){Files.writeString(root.resolve(AudienceSmokeServer.stage()+"-client-failure.json"),new Gson().toJson(Map.of("phase",phase,"error",String.valueOf(AudienceSmokeServer.failure))));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Audience fixture failed"));phase=98;after=ticks+20;return;}
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!trusted){
            if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!pressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.active&&button.getMessage().getString().equals("信任此服务器")){button.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});pressed=true;break;}
            String fingerprint=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");
            if(!fingerprint.isEmpty()&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fingerprint)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;mc.setScreen(null);}else return;
        }
        if(phase==0&&trusted&&AudienceSmokeServer.ready){host.open();phase=1;}
        if(phase==1&&host.ready()&&UiClientSessions.current()!=null){host.browser().executeJavaScript("document.querySelector('#open-tasks')?.click();",host.browser().getURL(),0);phase=2;after=ticks+30;}
        if(phase==2&&!busy&&ticks>=after&&AudienceSmokeServer.finished){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve(AudienceSmokeServer.stage()+"-generic-task-ui.png"));mc.execute(()->{busy=false;phase=3;});}catch(Exception failure){AudienceSmokeServer.failure="AUDIENCE_GUI_CAPTURE_FAILED";}});}
        if(phase==3){Files.writeString(root.resolve(AudienceSmokeServer.stage()+"-client-result.json"),new Gson().toJson(Map.of("scope","GENERIC_TASK_UI_ONLY","hostReady",host.ready(),"systemInputInjected",false,"audienceDeliveryVerified",false,"modelCalls",0)));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Audience fixture complete"));phase=90;after=ticks+20;}
    }
}
