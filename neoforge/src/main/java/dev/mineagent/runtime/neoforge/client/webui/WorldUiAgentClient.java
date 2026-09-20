package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.WorldUiProtocol;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** A new Agent document, never a rebind/copy of the player's projected private DOM. */
public final class WorldUiAgentClient {
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    private WorldUiAgentClient(){}
    public static CompletableFuture<Receipt> start(String view,UUID agent,String goal,String expected,UUID operation){
        var source=PackageContentClient.session(view);var shell=UiClientSessions.current();var mc=Minecraft.getInstance();
        if(source==null||shell==null||!WorldUiProtocol.bound(source.binding())||source.binding().actorKind()!=ActorKind.PLAYER)return CompletableFuture.failedFuture(new SecurityException("WORLD_UI_PLAYER_SOURCE_REQUIRED"));
        return UiClientSessions.command("worldui.agent",Map.of("sourceSessionId",source.sessionId().toString(),"agentId",agent.toString(),"goal",goal,"expected",expected,"confirmed","true"),operation).thenCompose(receipt->{
            if(receipt.code()!=Code.ACCEPTED)return CompletableFuture.completedFuture(receipt);
            Session target=JSON.fromJson(receipt.values().get("session"),Session.class);var launch=JSON.fromJson(receipt.values().get("launch"),WorldUiProtocol.Launch.class);
            try{
                WorldUiProtocol.requireAgentTransition(source,launch,agent);WorldUiProtocol.require(target,shell,launch,launch.canonicalSha256());
                if(!source.sessionId().toString().equals(receipt.values().get("sourceSessionId"))||!(mc.screen instanceof WebGuiInteractionScreen)||UiClientSessions.current()==null||!UiClientSessions.current().sessionId().equals(shell.sessionId())||PackageContentClient.session(view)==null||!PackageContentClient.session(view).sessionId().equals(source.sessionId()))throw new SecurityException("WORLD_UI_SOURCE_CLOSED");
                PackageContentClient.outdated(view,source.sessionId());
                return PackagePreviewClient.openWorld(launch,()->mc.getConnection()!=null&&UiClientSessions.current()!=null&&UiClientSessions.current().sessionId().equals(shell.sessionId())&&mc.screen instanceof WebGuiInteractionScreen).thenApply(opened->{
                    var values=new LinkedHashMap<>(receipt.values());values.putAll(opened.values());return new Receipt(receipt.operationId(),Code.ACCEPTED,values);
                }).whenComplete((v,e)->{if(e!=null)UiClientSessions.contentRequest("close",target,"worldui.read",Map.of(),UUID.randomUUID());});
            }catch(RuntimeException invalid){UiClientSessions.contentRequest("close",target,"worldui.read",Map.of(),UUID.randomUUID());return CompletableFuture.failedFuture(invalid);}
        });
    }
}
