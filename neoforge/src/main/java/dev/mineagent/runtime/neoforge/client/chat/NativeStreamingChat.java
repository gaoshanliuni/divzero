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
    private static final class Stream {
        String name;final UUID agent;final StringBuilder body=new StringBuilder(),thinking=new StringBuilder();final Set<String> parts=new HashSet<>();final MutableComponent approved=Component.empty();
        GuiMessage message;Component template;boolean done,suppressed,passthrough,lastAllowed=true;int expectedParts;String state="",color="#FFFFFF";
        Stream(String name,UUID agent){this.name=name;this.agent=agent;}
    }
    private record Part(UUID operation,UUID agent,String key,Component content){}
    private static Part part(Component value){
        if(value.getContents() instanceof TranslatableContents t&&t.getKey().equals(dev.mineagent.runtime.neoforge.chat.AiPlayerChat.STREAM_PART)&&t.getArgs().length==4)try{return new Part(UUID.fromString(t.getArgs()[1].toString()),UUID.fromString(t.getArgs()[2].toString()),t.getArgs()[3].toString(),t.getArgs()[0] instanceof Component c?c:Component.literal(t.getArgs()[0].toString()));}catch(IllegalArgumentException ignored){}
        if(value.getContents() instanceof TranslatableContents t)for(var arg:t.getArgs())if(arg instanceof Component c){var result=part(c);if(result!=null)return result;}
        for(var sibling:value.getSiblings()){var result=part(sibling);if(result!=null)return result;}return null;
    }
    private static Component replacePart(Component value,UUID operation,Component body){
        if(value.getContents() instanceof TranslatableContents t){
            if(t.getKey().equals(dev.mineagent.runtime.neoforge.chat.AiPlayerChat.STREAM_PART)&&t.getArgs().length==4&&operation.toString().equals(t.getArgs()[1].toString()))return body;
            Object[] args=t.getArgs().clone();for(int i=0;i<args.length;i++)if(args[i] instanceof Component c)args[i]=replacePart(c,operation,body);
            var result=Component.translatableWithFallback(t.getKey(),t.getFallback(),args).setStyle(value.getStyle());for(var sibling:value.getSiblings())result.append(replacePart(sibling,operation,body));return result;
        }
        var result=value.copy();result.getSiblings().clear();for(var sibling:value.getSiblings())result.append(replacePart(sibling,operation,body));return result;
    }
    private static boolean connected(){var mc=Minecraft.getInstance();if(connection!=mc.getConnection()){STREAMS.clear();connection=mc.getConnection();}return connection!=null;}
    /** Called after native/mod chat filtering has actually accepted or rejected this player's packet. */
    public static void nativeMessage(PlayerChatMessage message,com.mojang.authlib.GameProfile profile,boolean accepted){
        if(!connected())return;var part=part(message.decoratedContent());if(part==null||!part.agent.equals(message.sender()))return;
        var stream=STREAMS.computeIfAbsent(part.operation,id->new Stream(profile.name(),part.agent));stream.name=profile.name();stream.lastAllowed=accepted;
        boolean fresh=stream.parts.add(part.key);if(!accepted){paint(part.operation,stream);return;}
        var mc=Minecraft.getInstance();var all=((ChatHistoryAccess)mc.gui.getChat()).mineagent$messages();if(all.isEmpty())return;var added=all.getFirst();
        if(added.source()!=net.minecraft.client.multiplayer.chat.GuiMessageSource.PLAYER)return;
        var actual=part(added.content());if(actual==null||!actual.operation.equals(part.operation)){stream.passthrough=true;return;}
        if(stream.suppressed||!fresh){((ChatDisplayAccess)mc.gui.getChat()).mineagent$removeMessage(added);return;}
        stream.template=added.content();if(part.key.startsWith("body:"))stream.approved.append(actual.content.copy());
        if(stream.message==null)stream.message=added;else ((ChatDisplayAccess)mc.gui.getChat()).mineagent$removeMessage(added);
        paint(part.operation,stream);
    }
    public static void accept(UiPayloads.Event packet){
        if(!connected())return;
        try{
            var a=JsonParser.parseString(packet.json()).getAsJsonObject();UUID agent=UUID.fromString(a.get("agent").getAsString());String name=a.get("name").getAsString();if(name.length()>128)throw new IllegalArgumentException("CHAT_STREAM_NAME");
            var retained=Collections.newSetFromMap(new IdentityHashMap<GuiMessage,Boolean>());retained.addAll(((ChatHistoryAccess)Minecraft.getInstance().gui.getChat()).mineagent$messages());STREAMS.values().removeIf(s->s.done&&s.parts.size()>=s.expectedParts&&!retained.contains(s.message));
            var stream=STREAMS.computeIfAbsent(packet.requestId(),id->new Stream(name,agent));if(stream.done)return;if(!stream.agent.equals(agent))throw new IllegalArgumentException("CHAT_STREAM_SCOPE");stream.name=name;
            append(stream.body,a.get("bodyOffset").getAsInt(),a.get("body").getAsString());append(stream.thinking,a.get("thinkingOffset").getAsInt(),a.get("thinking").getAsString());
            stream.done=a.get("done").getAsBoolean();stream.state=a.get("state").getAsString();stream.color=a.get("color").getAsString();stream.expectedParts=a.get("nativeParts").getAsInt();if(!a.get("nativeAllowed").getAsBoolean())stream.lastAllowed=false;
            paint(packet.requestId(),stream);
        }catch(Exception error){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Native stream packet rejected: request={}, type={}",packet.requestId(),error.getClass().getSimpleName());}
    }
    private static void paint(UUID operation,Stream stream){
        var mc=Minecraft.getInstance();if(stream.suppressed||stream.passthrough||!stream.lastAllowed||stream.message==null||stream.template==null||mc.player==null||mc.isBlocked(stream.agent)||mc.options.onlyShowSecureChat().get()||!mc.player.chatAbilities().canReceivePlayerMessages())return;
        var text=stream.approved.copy();if(text.getString().isEmpty())text.append(Component.literal(stream.done?"":"…"));if(stream.done&&!stream.state.equals("COMPLETE")&&text.getString().isEmpty())text.append(Component.translatableWithFallback("mineagent.chat.stream.stopped","（已停止）"));
        if(!stream.done)text.append(Component.literal(" [打断]").withStyle(s->s.withColor(net.minecraft.ChatFormatting.YELLOW).withClickEvent(new ClickEvent.RunCommand("/ai interrupt "+stream.agent+" active:"+operation))));
        var answer=replacePart(stream.template,operation,text);
        var reasoning=Component.translatableWithFallback(NativeChatPreferencesClient.THINKING_KEY,"%s[思考]%s",AiChatMessages.name(stream.name),stream.thinking.toString()).withStyle(net.minecraft.ChatFormatting.GRAY);
        var content=Component.translatableWithFallback(KEY,"%s",answer,reasoning);var before=stream.message;var next=new GuiMessage(mc.gui.getGuiTicks(),content,before.signature(),before.source(),before.tag());
        ((ChatMessageClock)(Object)next).mineagent$receivedAt(((ChatMessageClock)(Object)before).mineagent$receivedAt());if(((ChatDisplayAccess)mc.gui.getChat()).mineagent$replaceMessage(before,next))stream.message=next;else stream.suppressed=true;
        if(stream.done&&stream.parts.size()>=stream.expectedParts){stream.body.setLength(0);stream.thinking.setLength(0);stream.body.trimToSize();stream.thinking.trimToSize();}
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
