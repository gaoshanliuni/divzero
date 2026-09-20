package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.task.ScheduleSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opens the real trusted question but never answers it or injects OS input. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ScheduleSmokeClient {
    private static int ticks,phase,after;private static int questionPaintAfter=-1;private static boolean backup,trusted,pressed,busy;private static JsonObject probe;
    public static void accept(JsonObject value){probe=value;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!ScheduleSmokeServer.enabled()||phase==99)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Path root=mc.gameDirectory.toPath().resolve("schedule-evidence");Files.createDirectories(root);
        if(phase==90){if(ticks>=after){phase=99;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SCHEDULE_{}_OK",ScheduleSmokeServer.stage().toUpperCase(Locale.ROOT));mc.stop();}return;}
        if(phase==98){if(ticks>=after){phase=99;mc.stop();}return;}
        if(ScheduleSmokeServer.failure!=null||ticks>3000){Files.writeString(root.resolve(ScheduleSmokeServer.stage()+"-client-failure.json"),new Gson().toJson(Map.of("phase",phase,"error",String.valueOf(ScheduleSmokeServer.failure))));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Schedule fixture failed"));phase=98;after=ticks+20;return;}
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!trusted){if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!pressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().equals("信任此服务器")){b.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});pressed=true;break;}
            String fp=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");if(!fp.isEmpty()&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fp)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;mc.setScreen(null);}else return;}
        if(phase==0&&trusted&&ScheduleSmokeServer.ready){host.open();phase=1;}
        if(!host.ready()||UiClientSessions.current()==null)return;
        if(phase==1){host.browser().executeJavaScript("document.querySelector("+new Gson().toJson(ScheduleSmokeServer.stage().equals("prepare")?"#open-tasks":"#open-decisions")+")?.click();",host.browser().getURL(),0);phase=2;after=ticks+30;}
        if(ScheduleSmokeServer.stage().equals("resume")&&ticks%10==0)host.browser().executeJavaScript("if(!document.body.innerText.includes('持久调度已到期')){const button=document.querySelector('#open-decisions');if(button&&Number(button.textContent.match(/\\d+/)?.[0]||0)>0)button.click();}window.mineagentQuery({request:JSON.stringify({channel:'scheduleSmokeProbe',questionVisible:document.body.innerText.includes('Native 调度唤醒')&&document.body.innerText.includes('持久调度已到期'),text:document.body.innerText.slice(0,12000)}),persistent:false,onSuccess(){},onFailure(){}});",host.browser().getURL(),0);
        if(phase==2&&ScheduleSmokeServer.stage().equals("resume")&&ScheduleSmokeServer.questionReady&&!ScheduleSmokeServer.questionCaptured&&!busy&&ticks>=after&&probe!=null&&probe.get("questionVisible").getAsBoolean()){
            if(questionPaintAfter<0){questionPaintAfter=ticks+20;return;}if(ticks<questionPaintAfter)return;
            busy=true;Files.writeString(root.resolve("resume-question-dom.json"),new Gson().toJson(probe));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve("resume-question.png"));mc.execute(()->{busy=false;ScheduleSmokeServer.questionCaptured=true;});}catch(Exception e){ScheduleSmokeServer.failure="SCHEDULE_QUESTION_CAPTURE_FAILED";}});
        }
        if(ticks%20==0&&probe!=null)Files.writeString(root.resolve(ScheduleSmokeServer.stage()+"-latest-probe.json"),new Gson().toJson(probe));
        if(phase==2&&!busy&&ticks>=after&&ScheduleSmokeServer.finished){Files.writeString(root.resolve(ScheduleSmokeServer.stage()+"-client-result.json"),new Gson().toJson(Map.of("hostReady",host.ready(),"questionCaptured",ScheduleSmokeServer.questionCaptured,"systemInputInjected",false,"realModel",false)));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Schedule fixture complete"));phase=90;after=ticks+20;}
    }
}
