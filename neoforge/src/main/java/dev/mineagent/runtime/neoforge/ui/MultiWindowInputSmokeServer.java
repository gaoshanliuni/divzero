package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
/** Isolated setup and authoritative two-task readback. No direct title writes. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class MultiWindowInputSmokeServer {
    public static volatile String agentA,agentB,sourceA,sourceB;
    public static volatile boolean verified;
    private static UUID pkg;
    public static boolean earlyHide(){return Boolean.getBoolean("mineagent.multiWindowEarlyHide");}
    public static String evidenceDirectory(){return earlyHide()?"multi-window-early-hide-evidence":Boolean.getBoolean("mineagent.multiWindowPointer")?"multi-window-pointer-evidence":"multi-window-evidence";}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.multiWindowInputSmoke"))return;
        var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var runtime=ServerPackageRuntime.get(server);var port=new NeoForgeScoreboardPort(server);var scores=MineAgentRuntimeServices.scoreboards(server);
        var json=new ObjectMapper();Path evidence=server.getServerDirectory().resolve(evidenceDirectory());Files.createDirectories(evidence);
        if(pkg==null){
            var job=runtime.list(player.getUUID()).stream().filter(j->j.operationId().toString().equals(System.getProperty("mineagent.multiWindowResume"))&&j.state().equals("PUBLISHED")).findFirst().orElseThrow();
            pkg=job.packageId();agentA=job.agentId().toString();
            var bodies=MineAgentRuntimeServices.bodies(server);var peer=bodies.definitions().stream().filter(a->a.displayName().equals("Input Peer Fixture")&&a.ownerPlayerId().equals(player.getUUID())).findFirst()
                    .orElseGet(()->bodies.create("Input Peer Fixture",player,AgentMode.CREATOR));agentB=peer.agentId().toString();
            var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(PermissionAction.MANAGE_SCOREBOARD);MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
            String nameA="mwa_"+UUID.randomUUID().toString().substring(0,8),nameB="mwb_"+UUID.randomUUID().toString().substring(0,8);
            for(String name:List.of(nameA,nameB)){port.createObjective(name,"dummy",name,"INTEGER",true,NumberFormatSpec.defaultFormat());port.setScore(name,"Alice",7);port.setScore(name,"Bob",2);}
            scores.refreshSources();sourceA=scores.sources().stream().filter(s->s.reference().equals(nameA)).findFirst().orElseThrow().sourceId().toString();
            sourceB=scores.sources().stream().filter(s->s.reference().equals(nameB)).findFirst().orElseThrow().sourceId().toString();
            Files.writeString(evidence.resolve("setup.json"),json.writeValueAsString(Map.of("packageId",pkg,"agentA",agentA,"agentB",agentB,"sourceA",sourceA,"sourceB",sourceB,"scores",port.snapshot())));
        }
        if(verified)return;
        var a=scores.views().stream().filter(v->v.ownerPackageId().equals(pkg)&&v.sourceId().toString().equals(sourceA)).findFirst().orElse(null);
        var b=scores.views().stream().filter(v->v.ownerPackageId().equals(pkg)&&v.sourceId().toString().equals(sourceB)).findFirst().orElse(null);if(a==null||b==null)return;
        com.fasterxml.jackson.databind.JsonNode resultA=null,resultB=null;
        for(var record:MineAgentRuntimeServices.audit(server).recent(96)){
            if(!Set.of("UI_AGENT_RESULT","UI_AGENT_STOPPED").contains(record.action()))continue;var value=json.readTree(record.payload());String target=value.path("session").path("binding").path("targetObjectId").asText();
            if(target.equals(a.viewId().toString())){Files.writeString(evidence.resolve("agent-a.json"),record.payload());if(!record.action().equals("UI_AGENT_STOPPED"))throw new IllegalStateException("EXPECTED_A_INTERRUPTION: "+value.path("status").asText());resultA=value;}
            if(target.equals(b.viewId().toString())){Files.writeString(evidence.resolve("agent-b.json"),record.payload());if(!record.action().equals("UI_AGENT_RESULT")||!value.path("status").asText().equals("VERIFIED"))throw new IllegalStateException("UNRELATED_AGENT_INTERRUPTED: "+value.path("status").asText());resultB=value;}
        }
        if(resultA==null||resultB==null)return;
        if(earlyHide()&&resultA.path("modelCalls").asInt(-1)!=0)throw new IllegalStateException("HIDDEN_VIEW_STARTED_MODEL");
        UUID taskA=UUID.fromString(resultA.path("session").path("binding").path("taskId").asText()),taskB=UUID.fromString(resultB.path("taskId").asText());
        if(MineAgentRuntimeServices.tasks(server).get(taskA).orElseThrow().status()!=TaskStatus.CANCELLED||MineAgentRuntimeServices.tasks(server).get(taskB).orElseThrow().status()!=TaskStatus.COMPLETED
                ||a.revision()!=1||b.revision()!=2||!b.layout().get("title").equals("并行排行榜 B")||resultA.path("operations").size()!=0)throw new IllegalStateException("MULTI_WINDOW_STATE_MISMATCH");
        for(var view:List.of(a,b)){var rows=scores.project(view.viewId()).rows();if(rows.stream().filter(r->r.holder().equals("Alice")).findFirst().orElseThrow().score()!=7||rows.stream().filter(r->r.holder().equals("Bob")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("MULTI_WINDOW_SCORE_CHANGED");}
        Files.writeString(evidence.resolve("server.json"),json.writeValueAsString(Map.of("viewA",a,"viewB",b,"taskA",MineAgentRuntimeServices.tasks(server).get(taskA).orElseThrow(),"taskB",MineAgentRuntimeServices.tasks(server).get(taskB).orElseThrow(),"scores",port.snapshot())));
        verified=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_MULTI_WINDOW_INPUT_SERVER_OK interruptedA=true completedB=true");
    }
}
