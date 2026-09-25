package dev.mineagent.runtime.neoforge.client.chat;

import dev.mineagent.runtime.neoforge.ui.NativeDeliverySmokeServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.*;import java.nio.file.*;

@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeDeliverySmokeClient {
    private static String active="",pending="";private static final StringBuilder body=new StringBuilder(),thinking=new StringBuilder();
    private static int ticks,parts;private static boolean done;
    private static void buttons(Component c){
        if(c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run){String command=run.command();
            if(command.startsWith("/ai interrupt "+NativeDeliverySmokeServer.a+" active:")&&active.isEmpty()){active=command;NativeDeliverySmokeServer.activeButtonReady=true;}
            if(command.startsWith("/ai interrupt "+NativeDeliverySmokeServer.a+" pending:")){pending=command;NativeDeliverySmokeServer.pendingButtonReady=true;}
        }for(var child:c.getSiblings())buttons(child);
    }
    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System event){if(!NativeDeliverySmokeServer.enabled())return;buttons(event.getMessage());String text=event.getMessage().getString();
        if(text.contains("CONVERSATION_REQUEST_FAILED")||text.contains("CONVERSATION_BUTTON_EXPIRED")||text.contains("CONVERSATION_PENDING_EXPIRED")||text.contains("聊天显示失败"))NativeDeliverySmokeServer.failure="NATIVE_CHAT_ERROR";
        String prefix="[显示乙][思考]";if(text.startsWith(prefix)){thinking.append(text.substring(prefix.length()));parts++;}
        else if(text.startsWith("[显示乙] ")&&!text.contains("正在处理")){body.append(text.substring("[显示乙] ".length()));parts++;}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!NativeDeliverySmokeServer.enabled()||done)return;var mc=Minecraft.getInstance();try{
        var root=Files.createDirectories(mc.gameDirectory.toPath().resolve("native-delivery-smoke"));if(!NativeDeliverySmokeServer.failure.isEmpty())throw new IllegalStateException(NativeDeliverySmokeServer.failure);if(++ticks>13000)throw new IllegalStateException("CLIENT_DELIVERY_TIMEOUT");if(mc.player==null)return;
        mc.options.pauseOnLostFocus=false;if(mc.screen!=null)mc.setScreen(null);
        if(!NativeDeliverySmokeServer.clientCommandSent){String phase=NativeDeliverySmokeServer.phase;if(Set.of("INTERRUPT_A","OLD_BUTTON","PENDING_BUTTON").contains(phase)){String command=phase.equals("PENDING_BUTTON")?pending:active;if(command.isEmpty())throw new IllegalStateException("NATIVE_BUTTON_MISSING");mc.player.connection.sendCommand(command.substring(1));NativeDeliverySmokeServer.clientCommandSent=true;}}
        if(NativeDeliverySmokeServer.complete&&body.toString().equals(NativeDeliverySmokeServer.expectedBody)&&thinking.toString().equals(NativeDeliverySmokeServer.expectedThinking)){
            Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("status","PASSED","nativeButtonCommandsUsed",true,"bodyMatchesDatabase",true,"thinkingMatchesDatabase",true,"segments",parts,"bodyChars",body.length(),"thinkingChars",thinking.length(),"osInputInjected",false)));done=true;mc.stop();
        }
    }catch(Exception e){try{Files.writeString(mc.gameDirectory.toPath().resolve("native-delivery-smoke/client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",e.toString(),"phase",NativeDeliverySmokeServer.phase,"bodyChars",body.length(),"thinkingChars",thinking.length())));}catch(Exception ignored){}done=true;mc.stop();}}
    private NativeDeliverySmokeClient(){}
}
