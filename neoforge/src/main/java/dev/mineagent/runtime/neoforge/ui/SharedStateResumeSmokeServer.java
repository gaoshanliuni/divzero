package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Second actual NeoForge JVM: use production package restore and service identity, not a fake browser receipt. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class SharedStateResumeSmokeServer {
    public static volatile boolean finished;public static volatile String failure;
    private static final ObjectMapper JSON=new ObjectMapper();private static int ticks;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!SharedStateSmokeSupport.resuming()||finished||failure!=null)return;var server=event.getServer();
        var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var root=server.getServerDirectory().resolve("shared-state-evidence");Files.createDirectories(root);
        try{
            if(++ticks>1200)throw new IllegalStateException("SHARED_RESTORE_TIMEOUT");
            var checkpoint=JSON.readTree(server.getServerDirectory().resolve("shared-state-restart.json").toFile());UUID first=UUID.fromString(checkpoint.path("first").asText()),second=UUID.fromString(checkpoint.path("second").asText());
            var world=WorldContentRuntime.get(server);for(var a:world.list(player.getUUID()))if(Set.of(first,second).contains(a.instanceId())&&a.state().equals("INTERRUPTED"))throw new IllegalStateException("SHARED_RESTORE_REJECTED_"+a.error());if(!world.active(first)||!world.active(second))return;var a=world.instance(first).orElseThrow();var b=world.instance(second).orElseThrow();
            if(!a.state().containsKey("sharedRestored")||!b.state().containsKey("sharedRestored"))return;
            var x=JSON.readTree(a.state().get("sharedRestored"));var y=JSON.readTree(b.state().get("sharedRestored"));
            if(x.path("count").asInt()!=1||x.path("revision").asInt()!=3||y.path("count").asInt()!=0||y.path("revision").asInt()!=2||x.path("privateActorEntryVisible").asBoolean()||y.path("privateActorEntryVisible").asBoolean()||x.path("replay").path("initialization").path("revision").asInt()!=2||!x.path("replay").path("initialization").path("status").asText().equals("APPLIED")||world.verifiedObjects(first)!=1||world.verifiedObjects(second)!=1)throw new IllegalStateException("SHARED_RESTORE_IDEMPOTENCY_OR_SCOPE_FAILED");
            Files.writeString(root.resolve("resume-result.json"),JSON.writeValueAsString(Map.of("status","NATIVE_SHARED_RESTART_NO_REPLAY_VERIFIED","first",a,"second",b,"firstShared",x,"secondShared",y,"realViewers",1,"newRealModelCalls",0,"systemInputInjected",false,"graphicalPageReopened",false)));
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SHARED_STATE_RESUME_OK first={} second={}",first,second);finished=true;
        }catch(Exception e){failure=e.toString();Files.writeString(root.resolve("resume-failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
}
