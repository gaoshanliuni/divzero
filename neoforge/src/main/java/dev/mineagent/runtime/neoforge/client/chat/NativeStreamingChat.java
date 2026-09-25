package dev.mineagent.runtime.neoforge.client.chat;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.chat.AiChatMessages;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;

/** One unsigned native history entry per request. Packet chunk boundaries never become display newlines. */
public final class NativeStreamingChat {
    public static final String KEY="mineagent.chat.stream";
    private static final Map<UUID,Stream> STREAMS=new LinkedHashMap<>();private static Object connection;
    private static final class Stream {final String name;final UUID agent;final StringBuilder body=new StringBuilder(),thinking=new StringBuilder();GuiMessage message;boolean done,suppressed;Stream(String name,UUID agent){this.name=name;this.agent=agent;}}
    public static void accept(UiPayloads.Event packet){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null)return;
        if(connection!=mc.getConnection()){STREAMS.clear();connection=mc.getConnection();}
        try{
            var a=JsonParser.parseString(packet.json()).getAsJsonObject();UUID agent=UUID.fromString(a.get("agent").getAsString());String name=a.get("name").getAsString();if(name.length()>128)throw new IllegalArgumentException("CHAT_STREAM_NAME");
            var stream=STREAMS.get(packet.requestId());if(stream==null){var retained=Collections.newSetFromMap(new IdentityHashMap<GuiMessage,Boolean>());retained.addAll(((ChatHistoryAccess)mc.gui.getChat()).mineagent$messages());STREAMS.values().removeIf(s->s.done&&!retained.contains(s.message));stream=new Stream(name,agent);STREAMS.put(packet.requestId(),stream);}
            if(stream.done)return;if(!stream.agent.equals(agent)||!stream.name.equals(name))throw new IllegalArgumentException("CHAT_STREAM_SCOPE");
            append(stream.body,a.get("bodyOffset").getAsInt(),a.get("body").getAsString());append(stream.thinking,a.get("thinkingOffset").getAsInt(),a.get("thinking").getAsString());
            stream.done=a.get("done").getAsBoolean();String body=stream.body.toString(),thought=stream.thinking.toString();
            var answer=AiChatMessages.line(name,body);if(body.isEmpty()&&stream.done&&!a.get("state").getAsString().equals("COMPLETE"))answer.append(Component.translatableWithFallback("mineagent.chat.stream.stopped","（已停止）"));
            if(!stream.done)answer.append(Component.literal(" [打断]").withStyle(s->s.withColor(net.minecraft.ChatFormatting.YELLOW).withClickEvent(new ClickEvent.RunCommand("/ai interrupt "+agent+" active:"+packet.requestId()))));
            String color=a.get("color").getAsString();if(color.matches("#[a-fA-F0-9]{6}"))answer.withStyle(s->s.withColor(Integer.parseInt(color.substring(1),16)));
            var reasoning=Component.translatableWithFallback(NativeChatPreferencesClient.THINKING_KEY,"%s[思考]%s",AiChatMessages.name(name),thought).withStyle(net.minecraft.ChatFormatting.GRAY);
            var content=Component.translatableWithFallback(KEY,"%s",answer,reasoning);var chat=mc.gui.getChat();var all=((ChatHistoryAccess)chat).mineagent$messages();
            if(!stream.suppressed){
                if(stream.message==null){chat.addServerSystemMessage(content);for(var m:all)if(m.content()==content){stream.message=m;break;}}
                else{var before=stream.message;var next=new GuiMessage(mc.gui.getGuiTicks(),content,null,before.source(),before.tag());((ChatMessageClock)(Object)next).mineagent$receivedAt(((ChatMessageClock)(Object)before).mineagent$receivedAt());if(((ChatDisplayAccess)chat).mineagent$replaceMessage(before,next))stream.message=next;else stream.suppressed=true;}
            }
            if(stream.done){stream.body.setLength(0);stream.thinking.setLength(0);stream.body.trimToSize();stream.thinking.trimToSize();}
        }catch(Exception error){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Native stream packet rejected: request={}, type={}",packet.requestId(),error.getClass().getSimpleName());}
    }
    private static void append(StringBuilder target,int offset,String text){
        if(offset<0||offset>target.length())throw new IllegalArgumentException("CHAT_STREAM_GAP");int overlap=Math.min(text.length(),target.length()-offset);
        if(!target.substring(offset,offset+overlap).equals(text.substring(0,overlap)))throw new IllegalArgumentException("CHAT_STREAM_CONFLICT");target.append(text,overlap,text.length());
    }
    public static Component body(Component content){if(content.getContents() instanceof TranslatableContents t&&t.getKey().equals(KEY)&&t.getArgs().length==2&&t.getArgs()[0] instanceof Component c)return c;return null;}
    public static Component thinking(Component content){if(content.getContents() instanceof TranslatableContents t&&t.getKey().equals(KEY)&&t.getArgs().length==2&&t.getArgs()[1] instanceof Component c)return c;return null;}
    public static List<FormattedCharSequence> lines(GuiMessage message,Font font,int width){
        Component body=body(message.content()),thought=thinking(message.content());if(body==null)return null;long at=((ChatMessageClock)(Object)message).mineagent$receivedAt();var out=new ArrayList<FormattedCharSequence>();
        if(thought!=null&&NativeChatPreferencesClient.showThinking()&&thought.getContents() instanceof TranslatableContents t&&t.getKey().equals(NativeChatPreferencesClient.THINKING_KEY)&&t.getArgs().length==2&&t.getArgs()[0] instanceof Component&&t.getArgs()[1] instanceof String text&&!text.isEmpty()){
            if(ChatMessageDisplayClient.thinkingMode().equals("tail")){
                var name=(Component)t.getArgs()[0];var prefix=Component.translatableWithFallback(NativeChatPreferencesClient.THINKING_KEY,"%s[思考]%s",name,"");
                int end=text.length();while(end>0&&(text.charAt(end-1)=='\n'||text.charAt(end-1)=='\r'))end--;int start=Math.max(text.lastIndexOf('\n',end-1),text.lastIndexOf('\r',end-1))+1;
                String tail=font.plainSubstrByWidth(text.substring(start,end),Math.max(0,width-font.width(prefix)),true);
                var line=Component.translatableWithFallback(NativeChatPreferencesClient.THINKING_KEY,"%s[思考]%s",name,tail).setStyle(thought.getStyle());
                var split=ComponentRenderUtils.wrapComponents(ChatMessageDisplayClient.decorate(line,at),width,font);if(!split.isEmpty())out.add(split.getFirst());
            }else out.addAll(ComponentRenderUtils.wrapComponents(ChatMessageDisplayClient.decorate(thought,at),width,font));
        }
        out.addAll(ComponentRenderUtils.wrapComponents(ChatMessageDisplayClient.decorate(body,at),width,font));return out;
    }
    private NativeStreamingChat(){}
}
