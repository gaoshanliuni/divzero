package dev.mineagent.runtime.neoforge.client.chat;

import dev.mineagent.runtime.neoforge.chat.AiChatMessages;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.*;
import net.minecraft.network.chat.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in native client regression. Operates only on its isolated local chat view; no world or model calls. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ChatMessageNativeSmoke {
    private static int ticks,phase,inserted,rpcTicks;private static boolean done;
    private static void require(boolean condition,String code){if(!condition)throw new IllegalStateException(code);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.chatMessageSmoke")||done)return;
        var mc=Minecraft.getInstance();if(mc.font==null||mc.gui==null||mc.getOverlay()!=null||mc.screen==null)return;
        if(Boolean.getBoolean("mineagent.chatMessageSmokeRequireWorld")&&mc.player==null)return;
        if(++ticks<30)return;var root=mc.gameDirectory.toPath().resolve("chat-message-smoke");
        try{
            Files.createDirectories(root);var chat=mc.gui.getChat();var all=((ChatHistoryAccess)chat).mineagent$messages();
            if(phase==0){require(ChatMessageDisplayClient.limit()==1024,"DEFAULT_LIMIT");chat.clearMessages(false);phase=1;}
            if(phase==1){for(int i=0;i<64&&inserted<1200;i++,inserted++)chat.addClientSystemMessage(AiChatMessages.line("回归AI"," message-"+inserted));if(inserted<1200)return;require(all.size()==1024,"DEFAULT_RETENTION");require(all.getLast().content().getString().endsWith("message-176"),"DEFAULT_OLDEST");ChatMessageDisplayClient.smokeConfigure(16384,"time");phase=2;inserted=0;}
            if(phase==2){for(int i=0;i<128&&inserted<16500;i++,inserted++)chat.addClientSystemMessage(AiChatMessages.line("回归AI"," max-"+inserted));if(inserted<16500)return;require(all.size()==16384,"MAX_RETENTION");require(all.getLast().content().getString().endsWith("max-116"),"MAX_OLDEST");phase=3;}
            if(phase==3){
                var signature=new MessageSignature(new byte[256]);var click=new ClickEvent.RunCommand("/ai msg limit 1024");
                var original=AiChatMessages.line("回归AI"," 原文保持").append(Component.literal(" [确认]").withStyle(s->s.withClickEvent(click)));
                chat.addPlayerMessage(original,signature,null);var message=all.getFirst();require(message.content()==original&&message.signature()==signature,"SIGNED_MESSAGE_MUTATED");
                long received=((ChatMessageClock)(Object)message).mineagent$receivedAt();require(received>0,"RECEIPT_TIME_NOT_CAPTURED");
                var hover=new AtomicBoolean();var button=new AtomicBoolean();var rendered=new StringBuilder();
                for(var line:message.splitLines(mc.font,2000))line.accept((index,style,cp)->{rendered.appendCodePoint(cp);if(style.getHoverEvent() instanceof HoverEvent.ShowText h&&h.value().getString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))hover.set(true);if(click.equals(style.getClickEvent()))button.set(true);return true;});
                require(rendered.toString().equals(original.getString()),"VISIBLE_PREFIX_ADDED");require(hover.get()&&button.get(),"NAME_HOVER_OR_CLICK_LOST");
                ChatMessageDisplayClient.smokeConfigure(1024,"记录于 {yyyy/MM/dd HH:mm:ss}");require(((ChatMessageClock)(Object)message).mineagent$receivedAt()==received,"HOVER_REFRESH_CHANGED_TIME");require(all.size()==1024,"SHRINK_RETENTION");
                var saved=chat.storeState();ChatMessageDisplayClient.smokeConfigure(17,"off");chat.restoreState(saved);require(all.size()==17,"RESTORE_EXCEEDED_LIMIT");
                var old=new GuiMessage(mc.gui.getGuiTicks()-100,original,signature,GuiMessageSource.PLAYER,null);((ChatMessageClock)(Object)old).mineagent$receivedAt(received-5000);all.addFirst(old);chat.deleteMessage(signature);var deleted=all.getFirst();require(deleted.signature()==null,"DELETION_FAILED");require(((ChatMessageClock)(Object)deleted).mineagent$receivedAt()==received-5000,"DELETION_CHANGED_TIME");
                ChatMessageDisplayClient.smokeConfigure(1024,"time");
                if(mc.player!=null){mc.player.connection.sendCommand("ai msg limit 4096");mc.player.connection.sendCommand("ai msg mark 记录于 {yyyy-MM-dd HH:mm:ss}");phase=4;}else finish(mc,root,false);
            }
            if(phase==4){
                if(++rpcTicks>400)throw new IllegalStateException("CHAT_COMMAND_ACK_TIMEOUT");
                long receipts=all.stream().filter(m->m.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t&&t.getKey().equals("mineagent.chat.messages.status")).count();
                var state=ChatMessageDisplayClient.smokeState();if(receipts>=2&&state.limit()==4096&&state.mark().equals("记录于 {yyyy-MM-dd HH:mm:ss}")){ChatMessageDisplayClient.smokeConfigure(1024,"time");finish(mc,root,true);}
            }

        }catch(Exception|LinkageError failure){done=true;try{Files.createDirectories(root);Files.writeString(root.resolve("failure.txt"),failure.toString());}catch(Exception ignored){}dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("Native chat message regression failed",failure);mc.stop();}
    }
    private static void finish(Minecraft mc,Path root,boolean commands)throws Exception{
        Files.writeString(root.resolve("result.json"),new com.google.gson.Gson().toJson(Map.of("status","NATIVE_CHAT_LIMIT_AND_NAME_HOVER_VERIFIED","defaultLimit",1024,"maximumLimit",16384,"visiblePrefixAdded",false,"signatureAndClickPreserved",true,"commandRoundTrip",commands,"modelRequests",0)));done=true;mc.stop();
    }
    private ChatMessageNativeSmoke(){}
}
