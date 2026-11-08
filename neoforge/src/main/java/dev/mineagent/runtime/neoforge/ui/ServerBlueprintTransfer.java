package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.task.ServerAuthority;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;

/** Authorized multiplayer uploads use the authenticated game connection, never a public filesystem HTTP port. */
final class ServerBlueprintTransfer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    static CompletableFuture<Map<String,Object>> handle(ServerPlayer player,Map<String,String> arguments,java.util.function.BooleanSupplier permit)throws Exception{
        ServerAuthority.require(player,PermissionAction.EDIT_WORLD);
        if(!arguments.keySet().equals(Set.of("agentId","path","body")))throw new IllegalArgumentException("BUILDING_TRANSFER_ARGUMENTS");
        UUID agent=UUID.fromString(arguments.get("agentId")),owner=player.getUUID();String path=arguments.get("path");JsonNode body=JSON.readTree(arguments.get("body"));
        var server=player.level().getServer();var permissions=dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.permissions(server);long authority=permissions.actionRevision(owner,PermissionAction.EDIT_WORLD);
        if(!body.isObject()||!Set.of("/list","/asset","/begin","/directory","/file","/chunk","/finish","/cancel","/select","/download").contains(path))throw new IllegalArgumentException("BUILDING_TRANSFER_ACTION");
        if(path.equals("/select"))return ServerBuildingFiles.selected(player,agent,UUID.fromString(body.path("id").asText()));
        return ServerBuildingFiles.library(player,agent).thenApplyAsync(store->{try{
            // Transfer IO is asynchronous; a still-open panel is not proof that the grant is still valid.
            boolean current=server.submit(()->permit.getAsBoolean()&&server.getPlayerList().getPlayer(owner)==player
                    &&ServerAuthority.allowed(player,PermissionAction.EDIT_WORLD)&&permissions.actionRevision(owner,PermissionAction.EDIT_WORLD)==authority
                    &&dev.mineagent.runtime.neoforge.task.ServerTaskStart.allowed(player,agent)).get(3,TimeUnit.SECONDS);
            if(!current)throw new IllegalStateException("BUILDING_TRANSFER_CONTEXT_CHANGED");
            return switch(path){
                case "/list"->store.list(owner,body.path("query").asText(""),body.path("offset").asInt(0));
                case "/asset"->Map.of("asset",store.asset(owner,UUID.fromString(body.path("id").asText())));
                case "/begin"->Map.of("upload",store.begin(owner,body.path("name").asText(),body.path("kind").asText("FILE")));
                case "/directory"->{store.directory(owner,UUID.fromString(body.path("upload").asText()),body.path("path").asText());yield Map.of("created",true);}
                case "/file"->{if(!body.path("bytes").isIntegralNumber()||!body.path("bytes").canConvertToLong())throw new IllegalArgumentException("BUILDING_TRANSFER_SIZE");yield Map.of("part",store.beginFile(owner,UUID.fromString(body.path("upload").asText()),body.path("path").asText(),body.path("bytes").asLong()));}
                case "/chunk"->{byte[] data=Base64.getDecoder().decode(body.path("bytes").asText());if(data.length<1||data.length>8192||!body.path("offset").isIntegralNumber())throw new IllegalArgumentException("BUILDING_TRANSFER_CHUNK");yield Map.of("offset",store.chunk(owner,UUID.fromString(body.path("upload").asText()),UUID.fromString(body.path("part").asText()),body.path("offset").asLong(),data));}
                case "/finish"->Map.of("asset",store.finish(owner,UUID.fromString(body.path("upload").asText()),"AUTHORIZED_MULTIPLAYER_UPLOAD"));
                case "/cancel"->{store.cancel(owner,UUID.fromString(body.path("upload").asText()));yield Map.of("cancelled",true);}
                case "/download"->{
                    UUID id=UUID.fromString(body.path("id").asText());var asset=store.asset(owner,id);var file=store.file(owner,id,"");
                    if(!asset.kind().equals("FILE")){
                        var archive=store.data(owner,id).getParent().resolve("authorized-download.zip");
                        synchronized(store){if(!java.nio.file.Files.exists(archive)){var pending=archive.resolveSibling("download-"+UUID.randomUUID()+".part");try(var output=java.nio.file.Files.newOutputStream(pending,java.nio.file.StandardOpenOption.CREATE_NEW)){store.archive(owner,id,output);}java.nio.file.Files.move(pending,archive,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}}file=archive;
                    }
                    long offset=body.path("offset").asLong(-1),total=java.nio.file.Files.size(file);if(offset<0||offset>total)throw new IllegalArgumentException("BUILDING_TRANSFER_OFFSET");
                    byte[] bytes=new byte[(int)Math.min(8192,total-offset)];try(var input=new java.io.RandomAccessFile(file.toFile(),"r")){input.seek(offset);input.readFully(bytes);}yield Map.of("bytes",Base64.getEncoder().encodeToString(bytes),"offset",offset+bytes.length,"total",total);
                }
                default->throw new IllegalArgumentException("BUILDING_TRANSFER_ACTION");
            };
        }catch(Exception failure){throw new CompletionException(failure);}},IO);
    }
    private ServerBlueprintTransfer(){}
}
