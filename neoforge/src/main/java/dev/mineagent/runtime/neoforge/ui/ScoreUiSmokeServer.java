package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Development fixture setup/readback only. Creation, binding, opening and title save must traverse real UI controls. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ScoreUiSmokeServer {
    private static UUID agent;
    private static UUID packageId;
    private static String objective;
    public static volatile String agentId,sourceId;
    public static volatile boolean titleVerified,externalChanged;
    public static volatile boolean closeRequested,closeVerified;
    public static volatile boolean interruptVerified;
    public static volatile boolean takeoverVerified;
    public static boolean agentMode(){return Boolean.getBoolean("mineagent.scoreUiAgent");}
    public static boolean toolMode(){return Boolean.getBoolean("mineagent.uiToolSmoke");}
    public static boolean coordinateMode(){return Boolean.getBoolean("mineagent.uiCoordinateSmoke");}
    public static String expectedTitle(){return toolMode()?"协作排行榜 · 已归档":"协作排行榜";}
    public static boolean interruptMode(){return agentMode()&&Boolean.getBoolean("mineagent.scoreUiInterrupt");}
    public static boolean takeoverMode(){return interruptMode()&&Boolean.getBoolean("mineagent.scoreUiTakeover");}
    public static String evidenceDirectory(){return coordinateMode()?"coordinate-evidence":toolMode()?"ui-tools-evidence":takeoverMode()?"takeover-evidence":interruptMode()?"agent-ui-interrupt-evidence":agentMode()?"agent-ui-evidence":"score-ui-evidence";}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception {
        if(!Boolean.getBoolean("mineagent.scoreUiSmoke"))return;
        var server=event.getServer();
        var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);
        if(player==null)return;
        var runtime=ServerPackageRuntime.get(server);
        var port=new NeoForgeScoreboardPort(server);
        Path evidence=server.getServerDirectory().resolve(evidenceDirectory()).toAbsolutePath().normalize();Files.createDirectories(evidence);
        var json=new ObjectMapper();
        if(agent==null){
            String resume=System.getProperty("mineagent.scoreUiResume","");
            if(resume.isBlank()){
                agent=MineAgentRuntimeServices.bodies(server).create("Score Fixture "+UUID.randomUUID().toString().substring(0,8),player,AgentMode.CREATOR).agentId();
                MineAgentRuntimeServices.permissions(server).registerOwnership(agent,player.getUUID());
            }else{
                var job=runtime.list(player.getUUID()).stream().filter(j->j.operationId().toString().equals(resume)&&j.state().equals("PUBLISHED")).findFirst().orElseThrow();
                agent=job.agentId();packageId=job.packageId();
            }
            var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(PermissionAction.MANAGE_SCOREBOARD);
            MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
            objective="ui_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
            if(!port.createObjective(objective,"dummy","预建验收目标","INTEGER",true,NumberFormatSpec.defaultFormat()).accepted())throw new IllegalStateException("SCORE_FIXTURE_SETUP");
            port.setScore(objective,"Alice",7);port.setScore(objective,"Bob",2);port.setDisplaySlot("sidebar",objective);
            sourceId=MineAgentRuntimeServices.scoreboards(server).refreshSources().stream().filter(s->s.reference().equals(objective)).findFirst().orElseThrow().sourceId().toString();
            agentId=agent.toString();Files.writeString(evidence.resolve("before.json"),json.writeValueAsString(port.snapshot()));
        }
        if(packageId==null){
            for(var job:runtime.list(player.getUUID()))if(job.agentId().equals(agent)){
                if(!job.state().equals("GENERATING")&&!job.state().equals("PUBLISHED"))throw new IllegalStateException("SCORE_UI_GENERATION_FAILED: "+job.errorCode());
                if(job.state().equals("PUBLISHED"))packageId=job.packageId();
            }
        }
        if(packageId==null)return;
        final UUID expectedPackage=packageId;
        var job=runtime.list(player.getUUID()).stream().filter(j->j.packageId().equals(expectedPackage)).findFirst().orElseThrow();
        var head=runtime.heads(player.getUUID()).stream().filter(p->p.packageId().equals(expectedPackage)).findFirst().orElseThrow();
        if(!Files.exists(evidence.resolve("package-"+packageId+"-r"+head.revision()+".json"))){
            var pkg=runtime.ownedPackage(player.getUUID(),packageId,head.revision()).orElseThrow();
            Files.writeString(evidence.resolve("package-"+packageId+"-r"+pkg.revision()+".json"),json.writeValueAsString(Map.of("generationJob",job,"currentPackage",pkg,"reopenedExisting",!System.getProperty("mineagent.scoreUiResume","").isBlank())));
            var store=new ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));
            Files.write(evidence.resolve("model-output-"+packageId+".json"),store.read(job.rawOutputSha256()));
            for(var ref:pkg.resources().values()){
                Path target=evidence.resolve("package").resolve(packageId.toString()).resolve(ref.path()).normalize();
                if(!target.startsWith(evidence))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,store.read(ref.sha256()));
            }
        }
        var view=MineAgentRuntimeServices.scoreboards(server).views().stream().filter(v->v.ownerPackageId().equals(expectedPackage)&&v.sourceId().toString().equals(sourceId)).findFirst().orElse(null);
        if(view==null)return;
        if(interruptMode()&&!interruptVerified){
            for(var record:MineAgentRuntimeServices.audit(server).recent(64)){
                if(!record.action().equals("UI_AGENT_STOPPED"))continue;var result=json.readTree(record.payload());
                if(!result.path("session").path("binding").path("targetObjectId").asText().equals(view.viewId().toString()))continue;
                var task=MineAgentRuntimeServices.tasks(server).get(UUID.fromString(record.target())).orElseThrow();
                if(task.status()!=dev.mineagent.runtime.api.task.TaskStatus.CANCELLED||view.revision()!=1||!view.layout().get("title").equals(objective)||result.path("operations").size()!=0)
                    throw new IllegalStateException("INTERRUPTED_AGENT_MUTATED_VIEW");
                var rows=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();
                if(rows.stream().filter(e->e.holder().equals("Alice")).findFirst().orElseThrow().score()!=7||rows.stream().filter(e->e.holder().equals("Bob")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("INTERRUPTED_AGENT_MUTATED_SCORE");
                Files.writeString(evidence.resolve("interrupt-result.json"),json.writeValueAsString(Map.of("audit",record,"view",view,"task",task,"scoreboard",port.snapshot())));
                interruptVerified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_AGENT_UI_INTERRUPT_SERVER_OK task={} viewUnchanged=true",task.taskId());break;
            }
        }
        if(agentMode()&&!interruptMode()&&!titleVerified){
            for(var record:MineAgentRuntimeServices.audit(server).recent(64)){
                if(!record.action().equals("UI_AGENT_RESULT"))continue;
                var result=json.readTree(record.payload());if(!result.path("session").path("binding").path("targetObjectId").asText().equals(view.viewId().toString()))continue;
                Files.writeString(evidence.resolve("agent-result.json"),record.payload());
                if(!result.path("status").asText().equals("VERIFIED")||!result.path("businessVerified").asBoolean())throw new IllegalStateException("AGENT_UI_NOT_VERIFIED: "+result.path("status").asText());
                var binding=result.path("session").path("binding");
                if(!binding.path("actorKind").asText().equals("AGENT")||!binding.path("actorId").asText().equals(agent.toString())||binding.path("viewerPlayerId").asText().equals(agent.toString()))throw new IllegalStateException("AGENT_UI_ACTOR_MISMATCH");
                boolean fill=false,click=false,key=false,drag=false;for(var a:result.path("outcome").path("actions")){String action=json.readTree(a.asText()).path("action").asText();fill|=action.equals("fill");click|=action.equals(coordinateMode()?"clickAt":"click");key|=action.equals("key");drag|=action.equals("drag");}
                if(!fill||(!toolMode()&&!click)||(toolMode()&&(!key||!drag))||result.path("operations").size()!=(toolMode()?2:1)||view.revision()!=(toolMode()?3:2))throw new IllegalStateException("AGENT_UI_ACTION_PROOF_MISSING");
                if(coordinateMode()){
                    boolean proof=false;var captures=new HashSet<String>();
                    for(var encoded:result.path("outcome").path("receipts")){var receipt=json.readTree(encoded.asText());
                        if(receipt.path("status").asText().equals("CAPTURED"))captures.add(receipt.path("capture").path("captureId").asText());
                        if(receipt.path("status").asText().equals("APPLIED_DOM")&&receipt.path("inputMode").asText().equals("DOM_COORDINATE_EVENTS")&&receipt.path("targetPixelsVerified").asBoolean()&&captures.contains(receipt.path("captureId").asText())&&!receipt.path("businessVerified").asBoolean())proof=true;
                    }
                    if(!proof)throw new IllegalStateException("COORDINATE_VISUAL_PROOF_MISSING");
                    String perturb=System.getProperty("mineagent.coordinatePerturb","");
                    if(!perturb.isBlank()){
                        int first=-1;String oldCapture="";var actions=result.path("outcome").path("actions");var receipts=result.path("outcome").path("receipts");
                        for(int i=0;i<actions.size();i++){var action=json.readTree(actions.get(i).asText());if(action.path("action").asText().equals("clickAt")){first=i;oldCapture=action.path("captureId").asText();break;}}
                        if(first<0||receipts.size()<=first)throw new IllegalStateException("COORDINATE_REFUSAL_MISSING");
                        var rejected=json.readTree(receipts.get(first).asText());String expected=perturb.equals("pixels")?"CAPTURE_TARGET_CHANGED":"STALE_CAPTURE";
                        if(!rejected.path("status").asText().equals(expected)||rejected.path("eventsDispatched").asInt()!=0)throw new IllegalStateException("STALE_COORDINATE_WAS_NOT_REJECTED");
                        boolean fresh=false;for(var encoded:receipts){var r=json.readTree(encoded.asText());if(r.path("inputMode").asText().equals("DOM_COORDINATE_EVENTS")&&r.path("status").asText().equals("APPLIED_DOM")&&!r.path("captureId").asText().equals(oldCapture))fresh=true;}
                        if(!fresh)throw new IllegalStateException("COORDINATE_RECAPTURE_MISSING");
                    }
                }
                if(toolMode()){
                    var kinds=new HashSet<String>();var ids=new HashSet<String>();
                    for(var a:result.path("outcome").path("actions")){var data=json.readTree(a.asText());kinds.add(data.path("action").asText());ids.add(data.path("operationId").asText());}
                    if(!kinds.containsAll(Set.of("verify","waitFor","scroll","key","drag","fill")))throw new IllegalStateException("UI_TOOLS_REQUIRED_ACTION_MISSING");
                    var observed=new HashSet<String>();
                    for(var receipt:result.path("outcome").path("receipts")){
                        var r=json.readTree(receipt.asText());if(!ids.remove(r.path("operationId").asText())||r.path("businessVerified").asBoolean())throw new IllegalStateException("UI_TOOLS_RECEIPT_IDENTITY");
                        if(r.path("executionMode").asText().equals("OBSERVATION")){
                            if(!r.path("status").asText().equals("MATCHED")||r.path("polls").asInt()<1)throw new IllegalStateException("UI_TOOLS_CONDITION_NOT_MATCHED");
                            observed.add(r.path("action").asText());
                        }else if(r.path("executionMode").asText().equals("VIEW_IMAGE")){
                            if(!r.path("status").asText().equals("CAPTURED")||r.path("capture").path("pngLength").asInt()<33)throw new IllegalStateException("UI_TOOLS_CAPTURE_MISSING");
                        }else if(!r.path("status").asText().equals("APPLIED_DOM"))throw new IllegalStateException("UI_TOOLS_DOM_NOT_APPLIED");
                    }
                    if(!ids.isEmpty()||!observed.containsAll(Set.of("verify","waitFor")))throw new IllegalStateException("UI_TOOLS_RECEIPT_MISSING");
                }
                if(MineAgentRuntimeServices.permissions(server).allowed(agent,false,PermissionAction.MANAGE_SCOREBOARD))throw new IllegalStateException("AGENT_HAS_UNEXPECTED_GLOBAL_SCORE_PERMISSION");
                var task=MineAgentRuntimeServices.tasks(server).get(UUID.fromString(result.path("taskId").asText())).orElseThrow();
                if(task.status()!=dev.mineagent.runtime.api.task.TaskStatus.COMPLETED)throw new IllegalStateException("AGENT_UI_TASK_NOT_COMPLETE");
                var rows=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();
                if(rows.size()!=2||rows.stream().filter(e->e.holder().equals("Alice")).findFirst().orElseThrow().score()!=7||rows.stream().filter(e->e.holder().equals("Bob")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("AGENT_UI_CHANGED_SCORES");
                Files.writeString(evidence.resolve("title-readback.json"),json.writeValueAsString(Map.of("view",view,"scoreboard",port.snapshot(),"task",task,"agentGlobalScorePermission",false)));
                titleVerified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_AGENT_UI_SERVER_VERIFIED task={} actor={} viewer={} modelCalls={}",task.taskId(),agent,player.getUUID(),result.path("modelCalls").asInt());break;
            }
        }
        if(!agentMode()&&!titleVerified&&view.layout().getOrDefault("title","").equals("协作排行榜")){
            var rows=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();
            if(rows.size()!=2||rows.stream().filter(e->e.holder().equals("Alice")).findFirst().orElseThrow().score()!=7
                    ||rows.stream().filter(e->e.holder().equals("Bob")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("TITLE_EDIT_CHANGED_SCORES");
            titleVerified=true;
            var ui=ServerUiRuntime.get(server);var session=ui.sessions().list(player.getUUID()).stream().filter(s->s.binding().targetObjectId().equals(view.viewId().toString())).findFirst().orElseThrow();
            var operations=ui.sessions().completedOperations(player.getUUID(),session.sessionId(),64).stream().filter(o->o.request().action().equals("scoreview.patch")).toList();
            if(operations.size()!=1||view.revision()!=2)throw new IllegalStateException("SCORE_UI_DUPLICATE_NOT_IDEMPOTENT");
            Files.writeString(evidence.resolve("title-readback.json"),json.writeValueAsString(Map.of("view",view,"scoreboard",port.snapshot(),"session",
                    session,"operations",operations)));
            // Independent external command, after proving the title save did not modify the scores.
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"scoreboard players add Alice "+objective+" 4");
            externalChanged=true;
            Files.writeString(evidence.resolve("external-command.json"),json.writeValueAsString(Map.of("command","scoreboard players add Alice "+objective+" 4","scoreboard",port.snapshot())));
            MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SCORE_UI_SERVER_TITLE_OK package={} view={} source={} externalChanged=true",packageId,view.viewId(),sourceId);
        }
        if(takeoverMode()&&interruptVerified&&!takeoverVerified){
            var matching=ServerUiRuntime.get(server).sessions().list(player.getUUID()).stream().filter(s->s.binding().targetObjectId().equals(view.viewId().toString())&&s.binding().actorKind()==dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.PLAYER).toList();
            for(var session:matching)if(session.status()==dev.mineagent.runtime.api.ui.UiProtocol.Status.RENDERED){
                if(session.binding().capabilities().equals(Set.of("scoreview.read"))){if(view.revision()!=1)throw new IllegalStateException("RESTORE_AUTO_SUBMITTED");Files.writeString(evidence.resolve("read-only-server.json"),json.writeValueAsString(Map.of("session",session,"view",view,"scoreboard",port.snapshot())));}
                if(session.binding().capabilities().contains("scoreview.patch")&&view.layout().get("title").equals("人工草稿")){
                    var ops=ServerUiRuntime.get(server).sessions().completedOperations(player.getUUID(),session.sessionId(),64).stream().filter(o->o.request().action().equals("scoreview.patch")).toList();
                    if(view.revision()!=2||ops.size()!=1||!Files.exists(evidence.resolve("read-only-server.json")))throw new IllegalStateException("TAKEOVER_WRITE_SEQUENCE");
                    var rows=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();if(rows.stream().filter(e->e.holder().equals("Alice")).findFirst().orElseThrow().score()!=7||rows.stream().filter(e->e.holder().equals("Bob")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("TAKEOVER_CHANGED_SCORES");
                    Files.writeString(evidence.resolve("takeover-server.json"),json.writeValueAsString(Map.of("session",session,"view",view,"operations",ops,"scoreboard",port.snapshot(),"audit",MineAgentRuntimeServices.audit(server).recent(16).stream().filter(a->a.action().startsWith("UI_TAKEOVER")).toList())));
                    takeoverVerified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_TAKEOVER_SERVER_OK actor=PLAYER restoredReadOnly=true explicitSave=true");
                }
            }
        }
        if(!takeoverMode()&&closeRequested&&(titleVerified||interruptVerified)&&!closeVerified&&ServerUiRuntime.get(server).sessions().list(player.getUUID()).stream().noneMatch(s->s.binding().targetObjectId().equals(view.viewId().toString()))){
            if(!view.enabled()||!view.layout().get("title").equals(interruptMode()?objective:expectedTitle())||port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)&&e.holder().equals("Alice")).findFirst().orElseThrow().score()!=(agentMode()?7:11))
                throw new IllegalStateException("EDITOR_CLOSE_CHANGED_WORLD_OR_VIEW");
            Files.writeString(evidence.resolve("closed-readback.json"),json.writeValueAsString(Map.of("view",view,"scoreboard",port.snapshot(),"contentSessionClosed",true)));
            closeVerified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SCORE_UI_CLOSE_OK viewRetained=true objectiveRetained=true");
        }
    }
}
