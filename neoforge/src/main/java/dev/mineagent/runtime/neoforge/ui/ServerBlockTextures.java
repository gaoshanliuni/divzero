package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import dev.mineagent.runtime.worker.web.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Image preparation off tick, then acknowledged client texture application bound to this player/world. */
public final class ServerBlockTextures {
    private static final ObjectMapper JSON=new ObjectMapper();private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private record Pending(ServerPlayer player,Object level,UUID agent,String kind,long expected,String hash,BooleanSupplier permit,CompletableFuture<Map<String,Object>> future){}
    private static final Map<UUID,Pending> PENDING=new ConcurrentHashMap<>();
    public static CompletableFuture<Map<String,Object>> search(JsonNode args){return CompletableFuture.supplyAsync(()->{try{return ImageSearchService.search(args.path("query").asText());}catch(Exception error){throw new CompletionException(error);}},IO);}
    private static boolean current(ServerPlayer p,UUID agent,Object level,BooleanSupplier permit){return permit.getAsBoolean()&&p.level()==level&&p.level().getServer().getPlayerList().getPlayer(p.getUUID())==p&&ServerTaskStart.allowed(p,agent);}
    public static CompletableFuture<Map<String,Object>> request(ServerPlayer p,UUID agent,String kind,JsonNode args,BooleanSupplier permit){
        var server=p.level().getServer();var level=p.level();if(!current(p,agent,level,permit))throw new SecurityException("BLOCK_TEXTURE_PERMISSION");
        String block=args.path("block_id").asText();var blockId=net.minecraft.resources.Identifier.tryParse(block);if(blockId==null||!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(blockId))throw new IllegalArgumentException("BLOCK_TEXTURE_BLOCK");
        long expected=args.path("expected_revision").asLong(-1);if(!kind.equals("inspect")&&(!args.path("expected_revision").isIntegralNumber()||!args.path("expected_revision").canConvertToLong()||expected<0))throw new IllegalArgumentException("BLOCK_TEXTURE_REVISION");
        CompletableFuture<ImageTexture.Image> image=kind.equals("set")?CompletableFuture.supplyAsync(()->{try{return ImageTexture.download(args.path("image_url").asText(),args.path("size").asInt(256),args.path("fit").asText("contain"));}catch(Exception e){throw new CompletionException(e);}},IO):CompletableFuture.completedFuture(null);
        var result=new CompletableFuture<Map<String,Object>>();image.whenComplete((asset,error)->server.execute(()->{
            try{
                if(error!=null){result.complete(Map.of("status","REJECTED","error",code(error),"clientChanged",false));return;}if(!current(p,agent,level,permit))throw new IllegalStateException("BLOCK_TEXTURE_CONTEXT_CHANGED");
                UUID operation=UUID.randomUUID();String hash=asset==null?"":asset.sha256();var pending=new Pending(p,level,agent,kind,expected,hash,permit,result);PENDING.put(operation,pending);
                result.orTimeout(120,TimeUnit.SECONDS).whenComplete((value,failure)->PENDING.remove(operation,pending));
                ObjectNode message=JSON.createObjectNode().put("kind",kind).put("world",MineAgentRuntimeServices.worldId(server).toString()).put("owner",p.getUUID().toString()).put("agent",agent.toString()).put("block",block).put("expectedRevision",expected);
                if(args.has("texture_id"))message.put("texture",args.path("texture_id").asText());
                byte[] bytes=asset==null?new byte[0]:asset.png();if(asset!=null)message.put("size",asset.size()).put("bytes",bytes.length).put("sha256",hash).put("sourceUrl",asset.sourceUrl()).put("chunks",(bytes.length+59999)/60000);
                send(p,operation,message);for(int offset=0,index=0;offset<bytes.length;offset+=60000,index++)send(p,operation,JSON.createObjectNode().put("kind","chunk").put("index",index).put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+60000)))));
            }catch(Exception failure){result.completeExceptionally(failure);}
        }));return result.exceptionally(error->Map.of("status",kind.equals("inspect")?"REJECTED":"UNKNOWN","error",code(error),"replayed",false));
    }
    private static void send(ServerPlayer player,UUID id,JsonNode data){net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new UiPayloads.Event(id,"blockTexture",data.toString()));}
    public static void reply(ServerPlayer player,UiPayloads.Command packet){
        var pending=PENDING.get(packet.requestId());if(pending==null||pending.player!=player||!current(player,pending.agent,pending.level,pending.permit)||packet.json().length()>65536)return;
        try{var reply=JSON.readTree(packet.json());String status=reply.path("status").asText();if(!Set.of("OBSERVED","APPLIED","REJECTED","UNKNOWN").contains(status))throw new IllegalArgumentException("BLOCK_TEXTURE_REPLY");
            if(status.equals("APPLIED")&&(pending.kind.equals("inspect")||reply.path("revision").asLong(-1)!=pending.expected+1||pending.kind.equals("set")&&!pending.hash.equals(reply.path("sha256").asText())))throw new IllegalArgumentException("BLOCK_TEXTURE_REPLY");
            if(status.equals("OBSERVED")&&!pending.kind.equals("inspect"))throw new IllegalArgumentException("BLOCK_TEXTURE_REPLY");pending.future.complete(JSON.convertValue(reply,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        }catch(Exception invalid){pending.future.complete(Map.of("status","UNKNOWN","error","BLOCK_TEXTURE_INVALID_ACK"));}PENDING.remove(packet.requestId(),pending);
    }
    private static String code(Throwable error){for(var cause=error;cause!=null;cause=cause.getCause()){var code=cause.getMessage();if(code!=null&&code.matches("(?:BLOCK_TEXTURE|IMAGE|WEB)_[A-Z0-9_]+"))return code;}return "BLOCK_TEXTURE_OUTCOME_UNKNOWN";}
    private ServerBlockTextures(){}
}
