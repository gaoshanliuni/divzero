package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;

/** Setup/readback only; patch submit/preview/apply/rollback are driven through the normal trusted GUI. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class UiPatchSmokeServer {
    public static final UUID PACKAGE=UUID.fromString("a10f493d-21c1-45f5-acb1-8aeb0270cf88");
    public static volatile String sourceId,targetViewId,operationId,state="SETUP";
    public static final boolean RESUMING=!System.getProperty("mineagent.uiPatchResumeToken","").isBlank();
    public static final String REQUEST_TOKEN=RESUMING?UUID.fromString(System.getProperty("mineagent.uiPatchResumeToken")).toString():UUID.randomUUID().toString();
    public static final String EVIDENCE_RUN=REQUEST_TOKEN+(RESUMING?"-reopen-"+UUID.randomUUID():"");
    public static final String MARKER="同包改版已应用 "+REQUEST_TOKEN.substring(0,8);
    public static volatile long baseRevision;
    public static volatile String baseView,candidateView,updatedView,rollbackView;
    public static volatile boolean staged,applied,rolledBack;
    private static String objective;private static RuntimePackage base;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.uiPatchSmoke"))return;var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var runtime=ServerPackageRuntime.get(server);var port=new NeoForgeScoreboardPort(server);var scores=MineAgentRuntimeServices.scoreboards(server);var json=new com.fasterxml.jackson.databind.ObjectMapper();Path root=server.getServerDirectory().resolve("ui-patch-evidence").resolve(EVIDENCE_RUN);Files.createDirectories(root);
        if(base==null){
            // Only this isolated GUI fixture's real viewer; no Agent permission/game-mode changes.
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            player.setInvulnerable(true);
            base=runtime.heads(player.getUUID()).stream().filter(p->p.packageId().equals(PACKAGE)).findFirst().orElseThrow();
            if(RESUMING){var prior=runtime.patchJobs(player.getUUID()).stream().filter(j->j.prompt().contains(REQUEST_TOKEN)).findFirst().orElseThrow();if(!prior.state().equals("READY")||prior.base().revision()!=base.revision()||!prior.base().canonicalSha256().equals(base.canonicalSha256()))throw new IllegalStateException("RESUME_REQUIRES_CURRENT_READY_CANDIDATE");}
            baseRevision=base.revision();
            var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(PermissionAction.MANAGE_SCOREBOARD);MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
            objective="patch_"+UUID.randomUUID().toString().replace("-","").substring(0,8);port.createObjective(objective,"dummy","Patch 原版目标","INTEGER",true,NumberFormatSpec.defaultFormat());port.setScore(objective,"Alice",7);port.setScore(objective,"Bob",2);port.setDisplaySlot("sidebar",objective);
            sourceId=scores.refreshSources().stream().filter(s->s.reference().equals(objective)).findFirst().orElseThrow().sourceId().toString();
            Files.writeString(root.resolve("before.json"),json.writeValueAsString(Map.of("package",base,"scoreboard",port.snapshot())));
        }
        var view=scores.views().stream().filter(v->v.ownerPackageId().equals(PACKAGE)&&v.sourceId().toString().equals(sourceId)).findFirst().orElse(null);if(view==null)return;targetViewId=view.viewId().toString();
        for(var s:ServerUiRuntime.get(server).sessions().list(player.getUUID()))if(s.binding().targetObjectId().equals(targetViewId)){
            if(s.binding().packageRevision()==base.revision())baseView=s.binding().viewId();
            if(s.binding().packageRevision()==base.revision()+1){if(s.binding().preview())candidateView=s.binding().viewId();else updatedView=s.binding().viewId();}
            if(s.binding().packageRevision()==base.revision()+2)rollbackView=s.binding().viewId();
        }
        var j=runtime.patchJobs(player.getUUID()).stream().filter(p->p.base().packageId().equals(PACKAGE)&&p.prompt().contains(REQUEST_TOKEN)).findFirst().orElse(null);if(j==null)return;
        operationId=j.operationId().toString();state=j.state();Files.writeString(root.resolve("patch-job.json"),json.writeValueAsString(j));
        var rows=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();
        if(view.revision()!=1||!view.layout().get("title").equals(objective)||rows.size()!=2||rows.stream().filter(e->e.holder().equals("Alice")).findFirst().orElseThrow().score()!=7||rows.stream().filter(e->e.holder().equals("Bob")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("UI_PATCH_MUTATED_BUSINESS_DATA");
        var head=runtime.heads(player.getUUID()).stream().filter(p->p.packageId().equals(PACKAGE)).findFirst().orElseThrow();
        if(j.state().equals("READY")&&!staged){
            if(head.revision()!=base.revision()||!head.canonicalSha256().equals(base.canonicalSha256()))throw new IllegalStateException("CANDIDATE_REPLACED_HEAD");
            Files.writeString(root.resolve("ready.json"),json.writeValueAsString(Map.of("head",head,"candidate",j.candidate(),"view",view,"scoreboard",port.snapshot())));
            var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));Files.write(root.resolve("model-output.json"),content.read(j.rawOutputSha256()));
            for(var ref:j.candidate().resources().values())if(ref.path().startsWith("ui/")){var file=root.resolve("candidate").resolve(ref.path());Files.createDirectories(file.getParent());Files.write(file,content.read(ref.sha256()));}
            if(!base.resources().get("ui/app.js").equals(j.candidate().resources().get("ui/app.js")))throw new IllegalStateException("PATCH_UNEXPECTED_HANDLER_CHANGE");staged=true;
        }
        if(j.state().equals("APPLIED")&&!applied){if(head.revision()!=base.revision()+1||!head.canonicalSha256().equals(j.candidate().canonicalSha256()))throw new IllegalStateException("PATCH_HEAD_MISMATCH");Files.writeString(root.resolve("applied.json"),json.writeValueAsString(Map.of("head",head,"view",view,"scoreboard",port.snapshot())));applied=true;}
        if(j.state().equals("ROLLED_BACK")&&!rolledBack){if(head.revision()!=base.revision()+2||!head.canonicalSha256().equals(base.canonicalSha256()))throw new IllegalStateException("ROLLBACK_HEAD_MISMATCH");Files.writeString(root.resolve("rolled-back.json"),json.writeValueAsString(Map.of("head",head,"view",view,"scoreboard",port.snapshot())));rolledBack=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_PATCH_SERVER_OK staged=true applied=true rolledBack=true scoresPreserved=true");}
        if(Boolean.getBoolean("mineagent.uiPatchIncompatible")&&j.state().equals("CANCELLED")){
            if(head.revision()!=base.revision()||!head.canonicalSha256().equals(base.canonicalSha256()))throw new IllegalStateException("INCOMPATIBLE_CANDIDATE_COMMITTED");
            Files.writeString(root.resolve("rejected.json"),json.writeValueAsString(Map.of("head",head,"job",j,"view",view,"scoreboard",port.snapshot())));
        }
        if(Set.of("FAILED","STALE","INTERRUPTED").contains(j.state()))throw new IllegalStateException("UI_PATCH_SMOKE_FAILED: "+j.errorCode());
    }
}
