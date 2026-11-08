package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.ui.UiProtocol;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Sends the production native UI protocol over each actual Minecraft connection. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class MultiplayerBlueprintSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();private static int ticks,stage;private static boolean done;private static String agent,upload,part,asset;private static CompletableFuture<?> pending;
    private static Minecraft mc(){return Minecraft.getInstance();}private static String name(){return System.getProperty("mineagent.blueprintSmokeName");}private static Path root(){return Path.of(System.getProperty("mineagent.multiplayerBlueprintRoot"));}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.multiplayerBlueprintSmoke")||done)return;
        try{
            if(++ticks>12000)throw new IllegalStateException("BLUEPRINT_CLIENT_TIMEOUT_"+stage);
            if(Files.exists(root().resolve(name()+"-verified.json"))){Files.writeString(root().resolve(name()+"-client-result.json"),JSON.writeValueAsString(Map.of("status","PASS","actualGameConnection",true,"modelCalls",0)));done=true;mc().stop();return;}
            if(mc().player==null||mc().getConnection()==null)return;
            if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled()){if(ticks%40==0)dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.acceptAll();return;}
            NativeWorkspaceConnection.open();if(!NativeWorkspaceConnection.ready())return;
            if(pending!=null){if(!pending.isDone())return;pending.join();pending=null;stage++;}
            if(stage==0){var ready=root().resolve(name()+"-ready.json");if(!Files.exists(ready))return;agent=JSON.readTree(Files.readString(ready)).path("agent").asText();stage=1;}
            if(stage==1){
                if(name().equals("BlueprintB")){pending=denied("/begin",Map.of("name","denied.nbt","kind","FILE"),"BlueprintB-denied.json");return;}stage=3;
            }
            if(stage==2){if(!Files.exists(root().resolve("BlueprintB-granted.json")))return;var foreign=JSON.readTree(Files.readString(root().resolve("BlueprintA-uploaded.json"))).path("asset").asText();pending=denied("/asset",Map.of("id",foreign),"BlueprintB-foreign-denied.json");return;}
            if(stage==3){pending=upload();return;}
        }catch(Throwable failure){try{Files.writeString(root().resolve(name()+"-failure.json"),JSON.writeValueAsString(Map.of("stage",stage,"error",failure.toString())));}catch(Exception ignored){}done=true;mc().stop();}
    }
    private static CompletableFuture<UiProtocol.Receipt> call(String path,Map<String,Object> data){return NativeWorkspaceConnection.command("files.transfer",Map.of("agentId",agent,"path",path,"body",JSON.valueToTree(data).toString()),UUID.randomUUID());}
    private static CompletableFuture<JsonNode> request(String path,Map<String,Object> data){return call(path,data).thenApply(receipt->{if(receipt.code()!=UiProtocol.Code.APPLIED)throw new IllegalStateException("BLUEPRINT_NATIVE_REQUEST_"+receipt);try{return JSON.readTree(receipt.values().get("result"));}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});}
    private static CompletableFuture<?> denied(String path,Map<String,Object> data,String evidence){return call(path,data).thenApply(receipt->{if(receipt.code()==UiProtocol.Code.APPLIED)throw new IllegalStateException("BLUEPRINT_NATIVE_MISSING_DENIAL");try{Files.writeString(root().resolve(evidence),JSON.writeValueAsString(Map.of("code",receipt.code(),"values",receipt.values())));return true;}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});}
    private static CompletableFuture<?> upload()throws Exception{
        byte[] bytes=dev.mineagent.runtime.core.building.BuildingNbt.encode(Map.of("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),"size",List.of(2,1,1),"palette",List.of(Map.of("Name","minecraft:stone"),Map.of("Name","minecraft:glass")),"blocks",List.of(Map.of("pos",List.of(0,0,0),"state",0),Map.of("pos",List.of(1,0,0),"state",1)),"entities",List.of()));
        return request("/begin",Map.of("name",name()+".nbt","kind","FILE")).thenCompose(row->{upload=row.path("upload").asText();return request("/file",Map.of("upload",upload,"path",name()+".nbt","bytes",bytes.length));}).thenCompose(row->{part=row.path("part").asText();return request("/chunk",Map.of("upload",upload,"part",part,"offset",0,"bytes",Base64.getEncoder().encodeToString(bytes)));}).thenCompose(row->{if(row.path("offset").asInt()!=bytes.length)throw new IllegalStateException("BLUEPRINT_CHUNK_OFFSET");return request("/finish",Map.of("upload",upload));}).thenCompose(row->{asset=row.path("asset").path("id").asText();return request("/list",Map.of("query","","offset",0));}).thenApply(row->{if(row.path("total").asInt()!=1||!row.path("files").get(0).path("id").asText().equals(asset))throw new IllegalStateException("BLUEPRINT_OWNER_LIBRARY_ISOLATION");try{Files.writeString(root().resolve(name()+"-uploaded.json"),JSON.writeValueAsString(Map.of("asset",asset,"bytes",bytes.length,"listingCount",1,"nativeGameConnection",true)));return true;}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
    }
    private MultiplayerBlueprintSmokeClient(){}
}
