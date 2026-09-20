package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.nio.file.*;
import java.util.*;
/** Shows the real generation/review UI; never answers the model's review question. */
final class FeedbackApplicationSmokeClient {
    private static int phase,ticks,after;private static boolean ended,busy;
    static boolean enabled(){return System.getProperty("mineagent.feedbackApplication","").equals("signup-generation");}
    static boolean tick(boolean trusted,JsonObject info)throws Exception{
        if(!enabled())return false;if(ended)return true;ticks++;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;
        if(ticks>12000)throw new IllegalStateException("APPLICATION_CLIENT_TIMEOUT");
        if(phase==0&&trusted&&info!=null&&info.get("ready").getAsBoolean()){host.open();phase=1;}
        if(phase==1&&host.ready()&&UiClientSessions.current()!=null){host.browser().executeJavaScript("document.querySelector('#open-generation').click();",host.browser().getURL(),0);phase=2;}
        if(phase==2&&info!=null&&info.get("reviewReady").getAsBoolean()){if(info.get("owner").getAsBoolean())host.browser().executeJavaScript("document.querySelector('#open-decisions').click();",host.browser().getURL(),0);phase=3;after=ticks+80;}
        if(phase==3&&ticks>=after&&!busy){busy=true;var root=mc.gameDirectory.toPath().resolve("feedback-application-evidence");Files.createDirectories(root);Files.writeString(root.resolve("generation-client-state.json"),new Gson().toJson(Map.of("info",info,"systemInputInjected",false,"answerSubmitted",false)));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve("generation-review-game.png"));mc.execute(()->{ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"deliveryFixture","{\"action\":\"done\"}"));busy=false;phase=4;after=ticks+20;});}catch(Exception e){mc.execute(()->{throw new IllegalStateException("APPLICATION_SCREENSHOT",e);});}});}
        if(phase==4&&(ticks>=after||mc.player==null)){ended=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_CLIENT_OK role={} realApplicationGeneration=true",System.getProperty("mineagent.deliverySmokeRole"));mc.stop();}return true;
    }
}
