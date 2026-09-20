package dev.mineagent.runtime.neoforge.client.chat;

import dev.mineagent.runtime.neoforge.ui.NativeMentionSmokeServer;
import dev.mineagent.runtime.neoforge.mixin.client.ChatSuggestionsAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import java.nio.file.*;
import java.util.*;

/** Exercises vanilla callbacks in an isolated game profile; never injects OS keyboard/mouse input. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeMentionSmokeClient {
    private static int ticks,phase,after;
    private static boolean reply,screenshot,finished,replayRejected;
    private static String completed="",quoted="",confirm="";
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("native-mention-smoke");}

    @SubscribeEvent public static void message(ClientChatReceivedEvent.System event){
        if(!Boolean.getBoolean("mineagent.nativeMentionSmoke"))return;
        String text=event.getMessage().getString();
        if(text.contains("[小明] NATIVE_MENTION_REPLY"))reply=true;
        if(text.contains("原操作状态：COMPLETED；没有重复执行。"))replayRejected=true;
        var click=event.getMessage().getStyle().getClickEvent();
        if(click instanceof net.minecraft.network.chat.ClickEvent.RunCommand run&&run.command().startsWith("/ai commands confirm "))confirm=run.command();
    }
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static EditBox input(ChatScreen screen){return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();}
    private static void key(ChatScreen screen,int key,int modifiers){require(screen.keyPressed(new KeyEvent(key,0,modifiers)),"MENTION_KEY_NOT_HANDLED_"+key);}

    private static void verifyCompletion(ChatScreen screen){
        var input=input(screen);
        var suggestions=((ChatSuggestionsAccess)screen).mineagent$suggestions();
        input.setValue("@");
        key(screen,264,0); // Down selects the second vanilla row.
        key(screen,258,0);
        require(input.getValue().equals("@小明 "),"MENTION_ARROW_SELECTION");
        key(screen,258,1); // Shift+Tab cycles backwards, just like vanilla player completion.
        require(input.getValue().equals("@\"AI Helper\" "),"MENTION_REVERSE_TAB");
        key(screen,256,0);
        require(!suggestions.isVisible()&&Minecraft.getInstance().screen==screen,"MENTION_ESCAPE_POPUP");
        input.setValue("@ai 小");key(screen,258,0);
        require(input.getValue().equals("@ai 小明 "),"MENTION_ALIAS_COMPLETION");
        input.setValue("@AI H");key(screen,258,0);
        quoted=input.getValue();require(quoted.equals("@\"AI Helper\" "),"MENTION_QUOTED_COMPLETION");
        input.setValue("/gamemo");suggestions.showSuggestions(false);key(screen,258,0);
        require(input.getValue().equals("/gamemode"),"MENTION_VANILLA_COMMAND_CHANGED");
        input.setValue("@小");key(screen,258,0);
        completed=input.getValue();require(completed.equals("@小明 "),"MENTION_TAB_RANGE");
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.nativeMentionSmoke")||finished)return;
        var mc=Minecraft.getInstance();ticks++;
        try{
            if(ticks>2000||!NativeMentionSmokeServer.failure.isEmpty())throw new IllegalStateException("MENTION_TIMEOUT_"+phase+" "+NativeMentionSmokeServer.failure);
            if(mc.player==null||!NativeMentionSmokeServer.ready)return;
            Files.createDirectories(root());
            if(phase==0){mc.setScreen(new ChatScreen("",false));phase=1;}
            if(phase==1&&NativeAgentChat.names().containsAll(List.of("小明","AI Helper"))){
                var screen=(ChatScreen)mc.screen;input(screen).setValue("@");
                require(((ChatSuggestionsAccess)screen).mineagent$suggestions().isVisible(),"MENTION_POPUP_MISSING");phase=2;after=ticks+10;
            }
            if(phase==2&&ticks>=after&&!screenshot){
                phase=3;
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),img->{try(img){img.writeToFile(root().resolve("native-suggestions.png"));screenshot=true;}catch(Exception failure){NativeMentionSmokeServer.failure=failure.toString();}});
            }
            if(phase==3&&screenshot){
                var screen=(ChatScreen)mc.screen;verifyCompletion(screen);
                input(screen).setValue(completed+"NATIVE_MENTION_HELLO");
                ((ChatSuggestionsAccess)screen).mineagent$suggestions().hide();
                screen.handleChatInput(input(screen).getValue(),true);mc.setScreen(null);phase=4;
            }
            if(phase==4&&reply&&NativeMentionSmokeServer.verified){mc.player.connection.sendChat("@小明 指令 NATIVE_COMMAND_REQUEST");phase=5;}
            if(phase==5&&!confirm.isEmpty()&&NativeMentionSmokeServer.reviewObserved){mc.player.connection.sendCommand(confirm.substring(1));phase=6;}
            if(phase==6&&NativeMentionSmokeServer.commandsVerified){mc.player.connection.sendCommand(confirm.substring(1));phase=7;}
            if(phase==7&&replayRejected){
                var result=new LinkedHashMap<String,Object>();
                result.put("status","NATIVE_AT_COMPLETION_PRIVATE_CHAT_COMMANDS_VERIFIED");result.put("completed",completed);result.put("quoted",quoted);
                result.put("completionChecks",List.of("popup","Tab","Down","Shift+Tab","Escape","alias","autoQuotes","vanillaCommand"));
                result.put("replyInVanillaChat",true);result.put("duplicateCommandConfirmRejected",true);result.put("screenshot",screenshot);
                result.put("systemInputInjected",false);result.put("paidCalls",0);
                Files.writeString(root().resolve("client.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
                finished=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_NATIVE_MENTION_OK");mc.stop();
            }
        }catch(Exception failure){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),failure.toString());finished=true;mc.stop();}
    }
}
