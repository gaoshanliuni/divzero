package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.interaction.EntityPartReplacement;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.*;

/** The server cannot inspect a client's baked resources. Bind replies to the exact requesting connection. */
public final class EntityModelProbe {
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Pending(ServerPlayer player,net.minecraft.server.level.ServerLevel level,String type,CompletableFuture<Map<String,Object>> future){}
    private static final Map<UUID,Pending> PENDING=new ConcurrentHashMap<>();
    public static CompletableFuture<Map<String,Object>> request(ServerPlayer p,JsonNode args)throws Exception{
        String type=args.path("entity_type").asText();var id=Identifier.tryParse(type);int offset=args.path("offset").asInt(0);
        if(id==null||!BuiltInRegistries.ENTITY_TYPE.containsKey(id)||type.equals("minecraft:player")||offset<0||args.has("offset")&&(!args.get("offset").isIntegralNumber()||!args.get("offset").canConvertToInt()))throw new IllegalArgumentException("ENTITY_MODEL_QUERY");
        var token=UUID.randomUUID();var future=new CompletableFuture<Map<String,Object>>();var pending=new Pending(p,p.level(),type,future);PENDING.put(token,pending);future.orTimeout(8,TimeUnit.SECONDS).whenComplete((v,e)->PENDING.remove(token,pending));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(token,"entityModelInspect",JSON.writeValueAsString(Map.of("entity_type",type,"offset",offset))));return future.exceptionally(e->Map.of("status","UNAVAILABLE","error","ENTITY_MODEL_CLIENT_NOT_AVAILABLE"));
    }
    public static void reply(ServerPlayer p,UiPayloads.Command packet){var pending=PENDING.get(packet.requestId());if(pending==null||pending.player()!=p||pending.level()!=p.level())return;
        try{var data=JSON.readValue(packet.json(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});if("CLIENT_MODEL_REFERENCE".equals(data.get("status"))&&!pending.type().equals(data.get("entity_type")))throw new IllegalArgumentException();pending.future().complete(data);}catch(Exception e){pending.future().complete(Map.of("status","UNAVAILABLE","error","ENTITY_MODEL_INVALID_REPLY"));}PENDING.remove(packet.requestId(),pending);
    }
    public static CompletableFuture<Map<String,Object>> replace(ServerPlayer p,UUID operation,JsonNode args)throws Exception{
        var source=JSON.createObjectNode();source.put("dimension",p.level().dimension().identifier().toString());
        for(String key:List.of("entity_id","entity_type","part_index"))if(args.has(key))source.set(key,args.get(key));
        var part=source.putObject("replacements").putObject(args.path("target_part").asText());for(String key:List.of("source_type","source_part","translation","rotation","scale"))if(args.has(key))part.set(key,args.get(key));
        var call=JSON.createObjectNode().put("source",source.toString());for(String key:List.of("rule_id","expected_revision"))if(args.has(key))call.set(key,args.get(key));
        return ServerEntityInterop.set(p,operation,call,"visual",false);
    }
    private EntityModelProbe(){}
}
