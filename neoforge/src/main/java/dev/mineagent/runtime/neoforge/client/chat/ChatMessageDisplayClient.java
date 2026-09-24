package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.client.control.ChatMessagePreferences;
import dev.mineagent.runtime.core.config.ChatMessageDisplay;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;import net.minecraft.network.chat.Component;
import com.google.gson.*;import java.time.*;import java.util.*;import java.util.concurrent.*;

public final class ChatMessageDisplayClient {
    private static final ChatMessageDisplay.State DEFAULT=new ChatMessageDisplay.State(1024,"time",0);
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static ChatMessagePreferences preferences;
    private static synchronized ChatMessagePreferences prefs(){if(preferences==null)try{preferences=new ChatMessagePreferences(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mineagent-chat-messages.properties"));}catch(Exception e){throw new IllegalStateException("CHAT_MESSAGES_SETTINGS_UNAVAILABLE",e);}return preferences;}
    private static ChatMessageDisplay.State state(){try{return prefs().state();}catch(Exception e){return DEFAULT;}}
    public static int limit(){return state().limit();}
    public static Component decorate(Component original,long at){try{return AiNameHover.apply(original,ChatMessageDisplay.hover(state().mark(),Instant.ofEpochMilli(at),ZoneId.systemDefault()));}catch(Exception e){return original;}}
    private static Map<String,Object> view(ChatMessageDisplay.State s,String status){return Map.of("status",status,"limit",s.limit(),"mark",s.mark(),"revision",s.revision(),"defaultLimit",1024,"maximumLimit",16384,"scope","CURRENT_CLIENT_ONLY","placement","AI_NAME_HOVER_ONLY","preview",ChatMessageDisplay.hover(s.mark(),Instant.now(),ZoneId.systemDefault()),"timezone",ZoneId.systemDefault().getId());}
    public static void accept(UiPayloads.Event packet){
        var mc=Minecraft.getInstance();var connection=mc.getConnection();if(connection==null)return;
        CompletableFuture.supplyAsync(()->{
            try{var a=JsonParser.parseString(packet.json()).getAsJsonObject();if(!Set.of("kind","limit","mark","expectedRevision").containsAll(a.keySet()))throw new IllegalArgumentException("CHAT_MESSAGES_ARGUMENTS");
                String kind=a.get("kind").getAsString();if(kind.equals("read"))return view(prefs().state(),"OBSERVED");if(!kind.equals("set"))throw new IllegalArgumentException("CHAT_MESSAGES_ARGUMENTS");
                Integer limit=null;String mark=null;Long revision=null;
                if(a.has("limit")){if(!a.get("limit").isJsonPrimitive()||!a.getAsJsonPrimitive("limit").isNumber()||!a.get("limit").getAsString().matches("[0-9]{1,5}"))throw new IllegalArgumentException("CHAT_MESSAGES_LIMIT_1_16384");limit=a.get("limit").getAsInt();}
                if(a.has("mark")){if(!a.get("mark").isJsonPrimitive()||!a.getAsJsonPrimitive("mark").isString())throw new IllegalArgumentException("CHAT_MESSAGES_MARK_INVALID");mark=a.get("mark").getAsString();}
                if(a.has("expectedRevision")){if(!a.get("expectedRevision").getAsString().matches("[0-9]{1,18}"))throw new IllegalArgumentException("CHAT_MESSAGES_REVISION");revision=a.get("expectedRevision").getAsLong();}
                if(limit==null&&mark==null)throw new IllegalArgumentException("CHAT_MESSAGES_ARGUMENTS");return view(prefs().save(revision,limit,mark),"APPLIED");
            }catch(Exception e){String code=Objects.toString(e.getMessage(),"");return Map.<String,Object>of("status","REJECTED","error",code.matches("CHAT_MESSAGES_[A-Z_0-9]+")?code:"CHAT_MESSAGES_SETTINGS_UNAVAILABLE");}
        },IO).thenAccept(result->mc.execute(()->{
            if(mc.getConnection()!=connection)return;
            Map<String,Object> reply=result;
            if("APPLIED".equals(result.get("status")))try{((ChatDisplayAccess)mc.gui.getChat()).mineagent$refreshMessageDisplay();}catch(RuntimeException failure){reply=Map.of("status","UNKNOWN","error","CHAT_MESSAGES_RENDER_REFRESH_FAILED");}
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new UiPayloads.Command(packet.requestId(),"chatMessageDisplayReply",new Gson().toJson(reply)));
        }));
    }
    /** Isolated production-JAR regression only; never called by a game/model channel. */
    static ChatMessageDisplay.State smokeState(){if(!Boolean.getBoolean("mineagent.chatMessageSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return prefs().state();}
    static void smokeConfigure(int limit,String mark)throws Exception{if(!Boolean.getBoolean("mineagent.chatMessageSmoke"))throw new IllegalStateException("SMOKE_DISABLED");prefs().save(null,limit,mark);((ChatDisplayAccess)Minecraft.getInstance().gui.getChat()).mineagent$refreshMessageDisplay();}
    private ChatMessageDisplayClient(){}
}
