package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.core.creature.CreatureDefinition;
import dev.mineagent.runtime.core.objects.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Prepare immutable render bundles before committing a new species revision. */
public final class CreatureModelAssets {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static ContentAddressedStore store(Path game,UUID world,UUID owner){return new ContentAddressedStore(game.resolve("mineagent-runtime-data/creature-models").resolve(world.toString()).resolve(owner.toString()),RuntimeModelBundle.MAX_BYTES);}
    public static CompletableFuture<Map<String,Object>> define(ServerPlayer p,UUID agent,UUID operation,JsonNode args,BooleanSupplier permit){
        var result=new CompletableFuture<Map<String,Object>>();
        try{
            var server=p.level().getServer();var level=p.level();var world=MineAgentRuntimeServices.worldId(server);var owner=p.getUUID();var game=server.getServerDirectory();
            if(!server.isSameThread()||!permit.getAsBoolean()||!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)||!allowed(p,agent))throw new SecurityException("CREATURE_PERMISSION");
            if(!args.isObject()||!Set.of("source","species_id","expected_revision","textures").containsAll(args.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("CREATURE_ARGUMENTS");
            ObjectNode source=(ObjectNode)JSON.readTree(args.path("source").asText());source.remove("model_assets");var draft=CreatureDefinition.parse(source.toString());
            var previous=args.has("species_id")?RuntimeCreatures.get(server).get(owner,UUID.fromString(args.path("species_id").asText())).orElseThrow(()->new IllegalArgumentException("CREATURE_NOT_OWNED")).definition():null;
            var references=args.path("textures");if(!references.isMissingNode()&&(!references.isObject()||references.size()>33))throw new IllegalArgumentException("CREATURE_TEXTURE_FILES");
            CompletableFuture<dev.mineagent.runtime.core.files.FileLibrary> files=references.isObject()&&!references.isEmpty()?ServerBuildingFiles.library(p,agent):CompletableFuture.completedFuture(null);
            var prepared=files.thenApplyAsync(library->{try{
                var bundles=store(game,world,owner);var assets=new LinkedHashMap<String,String>();var models=new LinkedHashMap<String,String>();models.put("_root",draft.model());
                for(var bone:source.path("bones"))if(bone.has("model"))models.put(bone.path("name").asText(),bone.get("model").toString());
                for(var entry:models.entrySet()){
                    if(!permit.getAsBoolean())throw new IllegalStateException("CREATURE_CANCELLED");var mesh=RuntimeMesh.parse(entry.getValue());if(mesh.texture().isEmpty())continue;byte[] png;
                    if(references.has(mesh.texture())){var id=references.get(mesh.texture());if(!id.isTextual())throw new IllegalArgumentException("CREATURE_TEXTURE_FILE_ID");var file=library.file(owner,UUID.fromString(id.asText()),"");if(!Files.isRegularFile(file)||Files.size(file)>RuntimeModelBundle.MAX_TEXTURE_BYTES)throw new IllegalArgumentException("CREATURE_TEXTURE_SIZE");png=Files.readAllBytes(file);}
                    else{
                        String old=previous==null?null:previous.modelAssets().get(entry.getKey());if(old==null)throw new IllegalArgumentException("CREATURE_TEXTURE_FILE_REQUIRED:"+mesh.texture());
                        var bundle=RuntimeModelBundle.decode(bundles.read(old),old);if(!bundle.mesh().texture().equals(mesh.texture()))throw new IllegalArgumentException("CREATURE_TEXTURE_FILE_REQUIRED:"+mesh.texture());png=bundle.texture();
                    }
                    var bundle=RuntimeModelBundle.create(entry.getValue().getBytes(StandardCharsets.UTF_8),png);
                    var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));if(image==null)throw new IllegalArgumentException("CREATURE_TEXTURE_INVALID_PNG");image.flush();
                    bundles.put(bundle.bytes());assets.put(entry.getKey(),bundle.sha256());
                }
                source.set("model_assets",JSON.valueToTree(assets));var updated=(ObjectNode)args.deepCopy();updated.remove("textures");updated.put("source",source.toString());return updated;
            }catch(Exception error){throw new CompletionException(error);}},IO);
            prepared.whenComplete((updated,error)->server.execute(()->{try{
                if(error!=null){result.complete(dev.mineagent.runtime.core.conversation.ToolFailure.result("define_creature",operation,error,dev.mineagent.runtime.core.conversation.ToolFailure.Phase.VALIDATION));return;}
                if(!permit.getAsBoolean()||server.getPlayerList().getPlayer(owner)!=p||p.level()!=level||!allowed(p,agent))throw new IllegalStateException("CREATURE_CONTEXT_CHANGED");
                result.complete(RuntimeCreatures.definePrepared(p,operation,updated));
            }catch(Exception failure){result.completeExceptionally(failure);}}));
        }catch(Exception error){result.complete(dev.mineagent.runtime.core.conversation.ToolFailure.result("define_creature",operation,error,dev.mineagent.runtime.core.conversation.ToolFailure.Phase.VALIDATION));}return result;
    }
    public static CompletableFuture<RuntimeModelBundle> read(MinecraftServer server,RuntimeCreatureEntity entity,String hash){
        var definition=entity.definition();if(definition==null||!definition.modelAssets().containsValue(hash)||entity.owner()==null)return CompletableFuture.failedFuture(new SecurityException("CREATURE_ASSET_NOT_BOUND"));
        var game=server.getServerDirectory();var world=MineAgentRuntimeServices.worldId(server);var owner=entity.owner();return CompletableFuture.supplyAsync(()->{try{return RuntimeModelBundle.decode(store(game,world,owner).read(hash),hash);}catch(Exception e){throw new CompletionException(e);}},IO);
    }
    private static boolean allowed(ServerPlayer p,UUID agent){return agent==null?MineAgentRuntimeServices.permissions(p.level().getServer()).allowed(p.getUUID(),p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE):dev.mineagent.runtime.neoforge.task.ServerTaskStart.allowed(p,agent);}
    private CreatureModelAssets(){}
}
