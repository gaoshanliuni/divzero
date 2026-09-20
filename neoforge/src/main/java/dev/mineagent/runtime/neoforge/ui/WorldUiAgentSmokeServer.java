package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.worker.generation.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Two explicit Native instances; real Agent identity and GUI task, controlled localhost planner. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldUiAgentSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static volatile UUID agent,first,second,firstEntity,secondEntity,worldId,packageId,completedTask;
    public static volatile int modelRequests;
    public static volatile boolean ready,bringNear,near,verified,finish,done;public static volatile String failure;public static volatile long revision;
    public static volatile boolean cancelFar;private static boolean farInjected;private static int cancelledAt=-1;
    public static volatile boolean queueBlocker,queuedReady,revokeQueued;private static boolean blockerSent,blockerComplete,probeSent,probeComplete,revokeDone;private static UUID fixtureOwner;
    private static dev.mineagent.runtime.neoforge.body.MineAgentPlayer body;private static UUID activation,secondActivation;private static boolean viewerWasOp;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public static boolean enabled(){return Boolean.getBoolean("mineagent.worldUiAgentSmoke");}
    public static String cancelMode(){return System.getProperty("mineagent.worldUiAgentCancelMode","");}
    public static boolean queuedMode(){return cancelMode().startsWith("queued-");}
    public static boolean realModel(){return !System.getProperty("mineagent.worldUiAgentModel","").isEmpty();}
    public static String providerMode(){return realModel()?"REAL_PROVIDER_PLANNING_ON_EXPLICIT_IMPORT":"CONTROLLED_LOCAL_HTTP_NOT_REAL_MODEL";}
    public static String directory(){return "world-ui-agent-evidence/"+RUN;}
    private static final String MODEL="{\"version\":1,\"boxes\":[{\"from\":[-0.4,0,-0.3],\"to\":[0.4,1.1,0.3],\"color\":\"#719879\"}],\"collision\":[-0.4,0,-0.3,0.4,1.1,0.3]}";
    private static final String SCRIPT="""
        function projection(e){var id=String(e.player().getUUID());return JSON.stringify({actor:id,label:String(content.state('label')),count:Number(content.state('count')),playerOnly:id===AGENT?'':'PLAYER_ONLY_SERVER_SECRET'});}
        on('instance.create',function(){content.state('label','initial');content.state('count','0');content.state('agentInteractions','0');content.createObject('console','models/control.json',0,0,0);});
        on('instance.restore',function(){content.createObject('console','models/control.json',0,0,0);});
        on('object.interact',function(e){if(String(e.player().getUUID())===AGENT)content.state('agentInteractions',String(Number(content.state('agentInteractions'))+1));content.openUi(e.player(),'ui');});
        on('ui.read',function(e){e.reply(projection(e));});
        on('ui.action',function(e){if(e.action()!=='rename')throw new Error('UNSUPPORTED');var data=JSON.parse(e.payload());if(typeof data.label!=='string')throw new Error('LABEL_REQUIRED');content.state('label',data.label);content.state('count',String(Number(content.state('count'))+1));content.state('actionActor',String(e.player().getUUID()));content.object('console').setYRot(77);e.reply(projection(e));});
        """;
    private static GeneratedFile file(String path,RuntimeResourceSide side,String media,byte[] data){return new GeneratedFile(path,side,media,dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(data),data);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        Path root=Files.createDirectories(server.getServerDirectory().resolve(directory()));var world=WorldContentRuntime.get(server);
        try{
            if(agent==null){
                var config=MineAgentRuntimeServices.config(server);config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true")),true);
                var permissions=MineAgentRuntimeServices.permissions(server);var allowed=new HashSet<>(permissions.trustedActions(viewer.getUUID()));allowed.addAll(Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES,PermissionAction.START_TASK));permissions.setTrustedActions(viewer.getUUID(),allowed);
                viewerWasOp=server.getPlayerList().isOp(viewer.nameAndId());server.getPlayerList().op(viewer.nameAndId());worldId=MineAgentRuntimeServices.worldId(server);
                for(int x=694;x<=712;x++)for(int z=-5;z<=8;z++){server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,79,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());for(int y=80;y<=86;y++)server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
                var studio=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse("mineagent_runtime:studio")));
                fixtureOwner=cancelMode().equals("queued-revoke")?UUID.randomUUID():viewer.getUUID();agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("Scoped UI Agent",fixtureOwner,studio,new net.minecraft.world.phys.Vec3(.5,105,.5)).agentId();body=MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow();
                if(cancelMode().equals("queued-revoke")){if(!MineAgentRuntimeServices.bodies(server).setCollaborator(agent,fixtureOwner,viewer.getUUID(),true))throw new IllegalStateException("FIXTURE_COLLABORATOR_SETUP");server.getPlayerList().deop(viewer.nameAndId());}
                String script="var AGENT='"+agent+"';\n"+SCRIPT;var js=file("server/main.js",RuntimeResourceSide.SERVER,"application/javascript",script.getBytes(StandardCharsets.UTF_8));var geometry=file("models/control.json",RuntimeResourceSide.COMMON,"application/json",MODEL.getBytes(StandardCharsets.UTF_8));var html=file("ui/index.html",RuntimeResourceSide.CLIENT,"text/html",Files.readAllBytes(Path.of(System.getProperty("mineagent.worldUiAgentFixture"))));
                var entry=new RuntimeEntrypoint(js.path(),js.side(),js.sha256());var def=new RuntimeDefinition(UUID.randomUUID(),"Actor isolated object",RuntimeDefinitionKind.ENTITY,"server",Set.of(geometry.path()),Map.of(),1);
                var pack=ServerPackageRuntime.get(server).importOwned(viewer,UUID.randomUUID(),new ParsedRuntimePackage("物件 Actor 隔离 · 本地夹具","1",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),Map.of("server",entry,"server.restore",entry,"ui",new RuntimeEntrypoint(html.path(),html.side(),html.sha256())),List.of(def),List.of(js,geometry,html)));packageId=pack.packageId();
                var a=world.activate(viewer,UUID.randomUUID(),packageId,pack.revision(),def.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",700.5,80,.5,0,0),true,false);if(!a.state().equals("ACTIVE"))throw new IllegalStateException("FIXTURE_FIRST_FAILED");activation=a.operationId();first=a.instanceId();
                revision=ServerPackageRuntime.get(server).worldLibrary().get(packageId).orElseThrow().revision();var b=world.activate(viewer,UUID.randomUUID(),packageId,revision,def.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",707.5,80,.5,0,0),true,false);if(!b.state().equals("ACTIVE"))throw new IllegalStateException("FIXTURE_SECOND_FAILED");second=b.instanceId();secondActivation=b.operationId();
                firstEntity=JSON.readValue(world.instance(first).orElseThrow().state().get("_object.console"),WorldContentRuntime.ObjectPart.class).entity();secondEntity=JSON.readValue(world.instance(second).orElseThrow().state().get("_object.console"),WorldContentRuntime.ObjectPart.class).entity();
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),700.5,81,4.5,Set.of(),180,8,true);
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("package",pack,"agent",agent,"viewer",viewer.getUUID(),"first",first,"second",second,"world",worldId,"viewerOp",viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),"agentOp",body.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),"origin","EXPLICIT_IMPORT","provider",providerMode())));Files.writeString(root.resolve("server.js"),script);
            }
            ready=world.verifiedObjects(first)==1&&world.verifiedObjects(second)==1;
            if(queueBlocker&&!blockerSent){blockerSent=true;MineAgentRuntimeServices.worker(server).complete(MineAgentRuntimeServices.config(server),new dev.mineagent.runtime.api.model.ModelRequest(dev.mineagent.runtime.api.model.ModelCapability.PLANNING,"CONTROLLED_UI_LANE_BLOCKER")).whenComplete((result,error)->server.execute(()->{if(error!=null||!String.valueOf(result.payload().get("text")).equals("CONTROLLED_BLOCKER_OK"))failure="CONTROLLED_BLOCKER_FAILED";else blockerComplete=true;}));}
            if(queuedMode()&&!queuedReady&&Files.exists(server.getServerDirectory().resolve("world-ui-agent-blocker-pending.json"))){
                for(var job:ServerUiRuntime.get(server).uiAgents().list(viewer.getUUID()))if(job.get("agentId").equals(agent.toString())&&job.get("status").equals("RUNNING")&&job.get("modelPending").equals("true")){
                    queuedReady=true;Files.writeString(root.resolve("queued-before-cancel.json"),JSON.writeValueAsString(Map.of("job",job,"blockerHttpInFlight",true,"uiCompleteReturnedPendingFuture",true)));break;
                }
            }
            if(revokeQueued&&!revokeDone){
                if(!queuedReady||viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new IllegalStateException("FIXTURE_REVOCATION_NOT_SCOPED");
                var manager=MineAgentRuntimeServices.bodies(server);if(!manager.setCollaborator(agent,fixtureOwner,viewer.getUUID(),false)||!manager.setCollaborator(agent,fixtureOwner,viewer.getUUID(),true))throw new IllegalStateException("FIXTURE_REVOKE_REGRANT_FAILED");revokeDone=true;
                Files.writeString(root.resolve("revoke-regrant.json"),JSON.writeValueAsString(Map.of("sameServerTick",server.getTickCount(),"viewerOp",false,"agentOwner",fixtureOwner,"viewer",viewer.getUUID(),"persistedDefinition",manager.definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElseThrow())));
            }
            if(bringNear&&!near){
                if(!body.level().dimension().identifier().toString().equals("mineagent_runtime:studio")||!world.instance(first).orElseThrow().state().get("agentInteractions").equals("0"))throw new IllegalStateException("AGENT_WAS_TELEPORTED_OR_INTERACTED_BY_ADMISSION");
                Files.writeString(root.resolve("far-proof.json"),JSON.writeValueAsString(Map.of("actorDimension",body.level().dimension().identifier().toString(),"agentInteractions",0,"sourceInstance",world.instance(first).orElseThrow())));
                body.teleportTo(server.overworld(),700.5,80,2.5,Set.of(),180,0,true);body.movementController().stop();near=true;
                Files.writeString(root.resolve("fixture-reposition.json"),JSON.writeValueAsString(Map.of("explicitFixtureSetup",true,"position",List.of(body.getX(),body.getY(),body.getZ()),"runtimeDelegationDidNotTeleport",true)));
            }
            if(cancelFar&&!farInjected){body.teleportTo(server.overworld(),720.5,80,2.5,Set.of(),180,0,true);farInjected=true;Files.writeString(root.resolve("far-cancel-injection.json"),JSON.writeValueAsString(Map.of("explicitFixtureMovement",true,"position",List.of(body.getX(),body.getY(),body.getZ()))));}
            if(!verified&&!cancelMode().isEmpty()){
                if(cancelledAt<0)for(var audit:MineAgentRuntimeServices.audit(server).recent(128))if(audit.action().equals("UI_AGENT_RESULT")&&JSON.readTree(audit.payload()).path("session").path("binding").path("actorId").asText().equals(agent.toString())){Files.writeString(root.resolve("unexpected-agent-result.json"),audit.payload());throw new IllegalStateException("WORLD_UI_EXPECTED_CANCELLATION_MISSING");}
                if(cancelledAt<0)for(var audit:MineAgentRuntimeServices.audit(server).recent(128))if(audit.action().equals("UI_AGENT_STOPPED")){
                    var result=JSON.readTree(audit.payload());if(!result.path("session").path("binding").path("actorId").asText().equals(agent.toString()))continue;
                    completedTask=UUID.fromString(result.path("session").path("binding").path("taskId").asText());Files.writeString(root.resolve("cancel-audit.json"),audit.payload());cancelledAt=server.getTickCount();
                    Files.writeString(server.getServerDirectory().resolve("world-ui-agent-provider-release"),"Explicit Native cancellation observed; release delayed local HTTP response.");break;
                }
                if(cancelledAt>=0){
                    var a=world.instance(first).orElseThrow();var b=world.instance(second).orElseThrow();
                    var task=MineAgentRuntimeServices.tasks(server).get(completedTask).orElseThrow();boolean taskStopped=cancelMode().equals("queued-revoke")?dev.mineagent.runtime.core.task.TaskAuthorityFence.revoked(task):task.status()==dev.mineagent.runtime.api.task.TaskStatus.CANCELLED;
                    if(!a.state().get("label").equals("initial")||!a.state().get("count").equals("0")||!a.state().get("agentInteractions").equals("1")||!b.state().get("count").equals("0")||((RuntimeObjectEntity)server.overworld().getEntity(firstEntity)).getYRot()!=0||body.taskControlOwned()||!taskStopped)throw new IllegalStateException("CANCELLED_WORLD_UI_APPLIED_LATE_ACTION");
                    if(queuedMode()&&blockerComplete&&!probeSent&&ServerUiRuntime.get(server).uiAgents().list(viewer.getUUID()).stream().filter(j->j.get("taskId").equals(completedTask.toString())).allMatch(j->j.get("modelPending").equals("false"))){
                        probeSent=true;MineAgentRuntimeServices.worker(server).complete(MineAgentRuntimeServices.config(server),new dev.mineagent.runtime.api.model.ModelRequest(dev.mineagent.runtime.api.model.ModelCapability.PLANNING,"CONTROLLED_UI_POST_CANCEL_PROBE")).whenComplete((result,error)->server.execute(()->{if(error!=null||!String.valueOf(result.payload().get("text")).equals("CONTROLLED_PROBE_OK"))failure="CONTROLLED_POST_CANCEL_WORKER_FAILED";else probeComplete=true;}));
                    }
                    if(server.getTickCount()-cancelledAt>=60&&(!queuedMode()||probeComplete)){verified=true;Files.writeString(root.resolve("cancel-verified.json"),JSON.writeValueAsString(Map.of("mode",cancelMode(),"first",a,"second",b,"task",task,"actorControlReleased",true,"ticksAfterCancellation",server.getTickCount()-cancelledAt,"delayedLocalHttpResponse",true,"queuedUiRequestCancelled",queuedMode(),"unrelatedWorkerProbeSucceeded",probeComplete)));}
                }
            }
            if(!verified&&cancelMode().isEmpty())for(var audit:MineAgentRuntimeServices.audit(server).recent(128))if(audit.action().equals("UI_AGENT_STOPPED")&&JSON.readTree(audit.payload()).path("session").path("binding").path("actorId").asText().equals(agent.toString())){Files.writeString(root.resolve("unexpected-stop.json"),audit.payload());throw new IllegalStateException("WORLD_UI_AGENT_INTERRUPTED_BEFORE_VERIFICATION");}
            if(!verified&&cancelMode().isEmpty())for(var audit:MineAgentRuntimeServices.audit(server).recent(128))if(audit.action().equals("UI_AGENT_RESULT")){
                var result=JSON.readTree(audit.payload());if(!result.path("session").path("binding").path("actorId").asText().equals(agent.toString()))continue;
                modelRequests=result.path("modelCalls").asInt();if(realModel()&&(!result.path("configuredModel").asText().equals(System.getProperty("mineagent.worldUiAgentModel"))||modelRequests<1))throw new IllegalStateException("REAL_WORLD_UI_MODEL_NOT_USED");
                Files.writeString(root.resolve("agent-result.json"),audit.payload());if(!viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)||body.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new IllegalStateException("AGENT_BORROWED_VIEWER_OP");if(!result.path("status").asText().equals("VERIFIED")||!result.path("verificationScope").asText().equals("AGENT_WORLD_UI_SERVER_PROJECTION"))throw new IllegalStateException("AGENT_WORLD_UI_NOT_VERIFIED");
                var a=world.instance(first).orElseThrow();var b=world.instance(second).orElseThrow();if(!a.state().get("label").equals("AGENT_CONTROL_OK")||!a.state().get("count").equals("1")||!a.state().get("actionActor").equals(agent.toString())||!a.state().get("agentInteractions").equals("1")||!b.state().get("count").equals("0")||!b.state().get("agentInteractions").equals("0")||((RuntimeObjectEntity)server.overworld().getEntity(firstEntity)).getYRot()!=77||((RuntimeObjectEntity)server.overworld().getEntity(secondEntity)).getYRot()!=0||body.taskControlOwned())throw new IllegalStateException("AGENT_NATIVE_SCOPE_OR_LEASE_FAILED");
                for(var observation:result.path("outcome").path("observations"))if(observation.asText().contains("PLAYER_ONLY_"))throw new IllegalStateException("PLAYER_PRIVATE_DATA_LEAKED");
                completedTask=UUID.fromString(result.path("taskId").asText());verified=true;Files.writeString(root.resolve("native-verified.json"),JSON.writeValueAsString(Map.of("first",a,"second",b,"actor",agent,"viewer",viewer.getUUID(),"actorControlReleased",true,"firstYaw",77,"secondYaw",0)));
            }
            if(finish){var stoppedFirst=world.disable(viewer,activation);var stoppedSecond=world.disable(viewer,secondActivation);if(!stoppedFirst.state().equals("DISABLED")||!stoppedSecond.state().equals("DISABLED"))throw new IllegalStateException("FIXTURE_TEARDOWN_FAILED");if(!viewerWasOp)server.getPlayerList().deop(viewer.nameAndId());done=true;Files.writeString(root.resolve("teardown.json"),JSON.writeValueAsString(Map.of("bothInstancesDisabled",true,"viewerOriginalOpRestored",server.getPlayerList().isOp(viewer.nameAndId())==viewerWasOp)));}
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
}
