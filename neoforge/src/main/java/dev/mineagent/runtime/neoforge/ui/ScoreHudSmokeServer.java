package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Isolated graphical fixture only: prepares vanilla data, never manufactures a generated HUD. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ScoreHudSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static volatile String agentId,sourceId,packageId,targetId;
    public static volatile boolean externalRequested,externalChanged,closeRequested,closeVerified;
    private static UUID agent;private static String objective;private static boolean copied;
    public static String directory(){return "score-hud-evidence/"+RUN;}
    public static boolean resume(){return !System.getProperty("mineagent.scoreHudResume","").isBlank();}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("mineagent.scoreHudSmoke"))return;
        if(Set.of("restore","closed","rejected").contains(System.getProperty("mineagent.hudPersistencePhase",""))){HudRestoreSmokeServer.tick(event);return;}
        var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var runtime=ServerPackageRuntime.get(server);var port=new NeoForgeScoreboardPort(server);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);
        if(agent==null){
            if(resume()){
                var job=runtime.generation(player.getUUID(),UUID.fromString(System.getProperty("mineagent.scoreHudResume"))).filter(j->j.state().equals("PUBLISHED")).orElseThrow();
                agent=job.agentId();packageId=job.packageId().toString();
            }else{agent=MineAgentRuntimeServices.bodies(server).create("HUD Fixture "+RUN.substring(0,8),player,AgentMode.CREATOR).agentId();MineAgentRuntimeServices.permissions(server).registerOwnership(agent,player.getUUID());}
            var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(PermissionAction.MANAGE_SCOREBOARD);MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
            objective="hud_"+RUN.substring(0,8);
            if(!port.createObjective(objective,"dummy","HUD 实时目标","INTEGER",true,NumberFormatSpec.defaultFormat()).accepted())throw new IllegalStateException("HUD_FIXTURE_SETUP");
            port.setScore(objective,"Alice",7);port.setScore(objective,"Bob",2);port.setDisplaySlot("sidebar",objective);
            sourceId=MineAgentRuntimeServices.scoreboards(server).refreshSources().stream().filter(s->s.reference().equals(objective)).findFirst().orElseThrow().sourceId().toString();
            agentId=agent.toString();Files.writeString(root.resolve("before-scoreboard.json"),json.writeValueAsString(port.snapshot()));
        }
        var job=runtime.list(player.getUUID()).stream().filter(j->j.agentId().equals(agent)).findFirst().orElse(null);if(job==null)return;
        if(!Set.of("GENERATING","PUBLISHED").contains(job.state()))throw new IllegalStateException("HUD_GENERATION_FAILED: "+job.errorCode());
        if(!job.state().equals("PUBLISHED"))return;packageId=job.packageId().toString();
        if(!copied){
            var pkg=runtime.heads(player.getUUID()).stream().filter(p->p.packageId().equals(job.packageId())).findFirst().orElseThrow();
            if(dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(pkg.entrypoints(),true).isEmpty())throw new IllegalStateException("GENERATED_HUD_ENTRY_MISSING");
            Files.writeString(root.resolve("package.json"),json.writeValueAsString(Map.of("job",job,"package",pkg,"reopenedExisting",resume())));
            var store=new ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));Files.write(root.resolve("model-output.json"),store.read(job.rawOutputSha256()));
            Path targetRoot=root.resolve("package").toAbsolutePath().normalize();
            for(var ref:pkg.resources().values()){Path path=targetRoot.resolve(ref.path()).normalize();if(!path.startsWith(targetRoot))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(path.getParent());Files.write(path,store.read(ref.sha256()));}
            copied=true;
        }
        var scores=MineAgentRuntimeServices.scoreboards(server);var view=scores.views().stream().filter(v->v.ownerPackageId().equals(job.packageId())&&v.sourceId().toString().equals(sourceId)).findFirst().orElse(null);if(view==null)return;targetId=view.viewId().toString();
        if(externalRequested&&!externalChanged){
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"scoreboard players set Alice "+objective+" 19");
            Files.writeString(root.resolve("external-scoreboard.json"),json.writeValueAsString(port.snapshot()));externalChanged=true;
        }
        if(closeRequested&&!closeVerified){
            var entries=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();
            if(entries.stream().filter(e->e.holder().equals("Alice")).findFirst().orElseThrow().score()!=19||view.revision()!=1||!view.enabled())throw new IllegalStateException("HUD_CLOSE_OR_WRITE_MUTATED_SOURCE");
            boolean remaining=ServerUiRuntime.get(server).sessions().list(player.getUUID()).stream().anyMatch(s->s.binding().ownerPackageId().equals(job.packageId()));
            if(remaining)return;
            Files.writeString(root.resolve("after-close.json"),json.writeValueAsString(Map.of("view",view,"scoreboard",port.snapshot(),"job",job,"allContentSessionsClosed",true)));
            closeVerified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SCORE_HUD_SERVER_OK run={}",RUN);
        }
    }
}
