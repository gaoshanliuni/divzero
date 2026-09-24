package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.config.ChatMessageDisplay;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;import java.util.concurrent.*;import java.util.function.BooleanSupplier;

/** Per-request acknowledgements bound to the requesting player's actual connection, independent of F2. */
public final class ServerChatMessageSettings {
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Pending(ServerPlayer player,Integer limit,String mark,CompletableFuture<Map<String,Object>> result){}
    private static final ConcurrentMap<UUID,Pending> PENDING=new ConcurrentHashMap<>();
    public static CompletableFuture<Map<String,Object>> request(ServerPlayer player,Integer limit,String mark,Long expected,BooleanSupplier permit){
        var server=player.level().getServer();
        if(!server.isSameThread()||!permit.getAsBoolean()||server.getPlayerList().getPlayer(player.getUUID())!=player)return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","CHAT_MESSAGES_CONTEXT_CHANGED"));
        try{
            if(limit!=null)ChatMessageDisplay.requireLimit(limit);if(mark!=null)ChatMessageDisplay.validateMark(mark);if(expected!=null&&expected<0)throw new IllegalArgumentException("CHAT_MESSAGES_REVISION");
            var args=new LinkedHashMap<String,Object>();boolean write=limit!=null||mark!=null;args.put("kind",write?"set":"read");if(limit!=null)args.put("limit",limit);if(mark!=null)args.put("mark",mark);if(expected!=null)args.put("expectedRevision",expected);
            UUID id=UUID.randomUUID();var result=new CompletableFuture<Map<String,Object>>();var pending=new Pending(player,limit,mark,result);PENDING.put(id,pending);
            result.orTimeout(10,TimeUnit.SECONDS).whenComplete((v,e)->PENDING.remove(id,pending));
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new UiPayloads.Event(id,"chatMessageDisplay",JSON.writeValueAsString(args)));
            return result.exceptionally(e->Map.of("status",write?"UNKNOWN":"REJECTED","error","CHAT_MESSAGES_CLIENT_ACK_TIMEOUT","replayed",false));
        }catch(Exception e){String code=Objects.toString(e.getMessage(),"");return CompletableFuture.completedFuture(Map.of("status","REJECTED","error",code.matches("CHAT_MESSAGES_[A-Z_0-9]+")?code:"CHAT_MESSAGES_ARGUMENTS"));}
    }
    public static void reply(ServerPlayer player,UiPayloads.Command packet){
        var pending=PENDING.get(packet.requestId());if(pending==null||pending.player()!=player||player.level().getServer().getPlayerList().getPlayer(player.getUUID())!=player||packet.json().length()>4096)return;
        try{
            var json=JSON.readTree(packet.json());String status=json.path("status").asText();
            if(Set.of("APPLIED","OBSERVED").contains(status)){
                if(!json.path("limit").isIntegralNumber()||!json.path("limit").canConvertToInt()||!json.path("mark").isTextual()||!json.path("revision").isIntegralNumber()||!json.path("revision").canConvertToLong())throw new IllegalArgumentException();
                var state=new ChatMessageDisplay.State(json.path("limit").intValue(),json.path("mark").textValue(),json.path("revision").longValue());
                if(pending.limit()!=null&&!pending.limit().equals(state.limit())||pending.mark()!=null&&!pending.mark().equals(state.mark()))throw new IllegalArgumentException();
                if((pending.limit()!=null||pending.mark()!=null)!=status.equals("APPLIED"))throw new IllegalArgumentException();
                pending.result().complete(Map.of("status",status,"limit",state.limit(),"mark",state.mark(),"revision",state.revision(),"scope","CURRENT_CLIENT_ONLY","placement","AI_NAME_HOVER_ONLY","defaultLimit",1024,"maximumLimit",16384));
            }else{String code=json.path("error").asText();if(!Set.of("REJECTED","UNKNOWN").contains(status)||!code.matches("CHAT_MESSAGES_[A-Z_0-9]+"))throw new IllegalArgumentException();pending.result().complete(Map.of("status",status,"error",code));}
        }catch(Exception failure){pending.result().complete(Map.of("status","UNKNOWN","error","CHAT_MESSAGES_INVALID_ACK"));}
        PENDING.remove(packet.requestId(),pending);
    }
    private ServerChatMessageSettings(){}
}
