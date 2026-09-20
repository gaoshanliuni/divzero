package dev.mineagent.runtime.neoforge.task;

import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.TaskStatus;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Explicit repair of an already generated package in the same isolated profile. Never re-generates the package. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldPatchSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static final String PROMPT="修复当前悬浮棱镜摆的初始 AABB 重叠：base 碰撞 X 范围[-0.8,0.8]，left/right 在 X=-0.9/+0.9 时相交。请仅修改 server/main.js，把 left/right 创建位置和各自 spring 世界锚点的 X 偏移改为 -1.4/+1.4，确保挂饰在架外。保留三件不同几何、颜色、所有 partKey、clicks/pulse、右挂饰点击上抛、restore 与其余参数；不要修改模型资源或 ui，不自动执行，不新建包。";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public static volatile String agentId,packageId,patchOperation,candidateHash,failure;
    public static volatile long revision;public static volatile RuntimeInstanceLocation location;
    public static volatile boolean allowApply,allowNative,active,closedGui,independent,interacted,disabled,done;
    public static volatile Map<String,UUID> objects=Map.of();
    public static volatile UUID activationId;
    private static UUID instance;private static boolean setup,exported,checked;private static long closeTick,pulseBefore,disableTick;private static List<RuntimeObjectFreezeProof.Pose> frozen;
    private static int clickTick=-1;private static double priorY,startY,maxY;
    private static long restartTick=-1,restartWait=-1;private static Map<String,String> restartState;
    public static boolean verifyOnly(){return Boolean.getBoolean("mineagent.worldPatchVerifyOnly");}
    public static String directory(){return "world-patch-evidence/"+RUN;}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldPatchSmoke")||done||failure!=null)return;var server=event.getServer();
        var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        Path root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);var runtime=ServerPackageRuntime.get(server);var world=WorldContentRuntime.get(server);
        try{
            var original=runtime.generation(viewer.getUUID(),UUID.fromString(System.getProperty("mineagent.worldPatchGeneration"))).filter(j->j.state().equals("PUBLISHED")).orElseThrow();
            if(!setup){
                agentId=original.agentId().toString();packageId=original.packageId().toString();
                for(var task:MineAgentRuntimeServices.tasks(server).all())if(task.agentId().equals(original.agentId())&&task.status()==TaskStatus.RUNNING&&task.steps().stream().anyMatch(s->Set.of("plan","execute").contains(s.stepId())))MineAgentRuntimeServices.tasks(server).transition(task.taskId(),task.revision(),true,TaskStatus.PAUSED);
                try(var folders=Files.list(server.getServerDirectory().resolve("world-package-task-evidence"))){for(var p:folders.filter(Files::isDirectory).toList())if(Files.isRegularFile(p.resolve("generation.json"))&&JSON.readTree(p.resolve("generation.json").toFile()).path("operationId").asText().equals(original.operationId().toString())){location=JSON.treeToValue(JSON.readTree(p.resolve("fixture.json").toFile()).get("location"),RuntimeInstanceLocation.class);break;}}
                if(location==null)throw new IllegalStateException("ORIGINAL_FIXTURE_LOCATION_MISSING");
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));grants.addAll(Set.of(PermissionAction.START_TASK,PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES));MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),grants);
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),location.x(),location.y()+1,location.z()+4,Set.of(),180,10,true);
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("run",RUN,"originalGeneration",original,"location",location,"repairPrompt",PROMPT,"originalTaskNotReplayed",true)));setup=true;
            }
            var job=runtime.worldPatchJobs(viewer.getUUID()).stream().filter(j->j.base().packageId().equals(original.packageId())&&j.base().canonicalSha256().equals(original.canonicalSha256())&&j.prompt().equals(PROMPT)).findFirst().orElse(null);
            var head=runtime.worldLibrary().get(original.packageId()).orElseThrow();revision=head.revision();
            if(job==null)return;patchOperation=job.operationId().toString();Files.writeString(root.resolve("patch-job.json"),JSON.writeValueAsString(job));
            if(Set.of("FAILED","STALE","INTERRUPTED","CANCELLED").contains(job.state()))throw new IllegalStateException("WORLD_PATCH_TERMINAL_"+job.state()+"_"+job.errorCode());
            if(job.candidate()==null)return;candidateHash=job.candidate().canonicalSha256();
            if(!exported){
                Files.write(root.resolve("model-output.json"),runtime.worldContent().read(job.rawOutputSha256()));Files.writeString(root.resolve("candidate.json"),JSON.writeValueAsString(job.candidate()));
                Path files=root.resolve("candidate").toAbsolutePath().normalize();for(var ref:job.candidate().resources().values()){var target=files.resolve(ref.path()).normalize();if(!target.startsWith(files))throw new IllegalStateException("WORLD_PATCH_EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,runtime.worldContent().read(ref.sha256()));}exported=true;
            }
            if(job.state().equals("READY")&&!checked){
                try{runtime.worldPatchAction(viewer.getUUID(),job.operationId(),"apply",false,candidateHash);throw new IllegalStateException("UNCONFIRMED_PATCH_ACCEPTED");}catch(SecurityException expected){}
                try{runtime.worldPatchAction(viewer.getUUID(),job.operationId(),"apply",true,"0".repeat(64));throw new IllegalStateException("WRONG_HASH_PATCH_ACCEPTED");}catch(SecurityException expected){}
                try{runtime.patchAction(viewer.getUUID(),job.operationId(),"apply");throw new IllegalStateException("UI_ROUTE_ACCEPTED_WORLD_PATCH");}catch(SecurityException expected){}
                if(!runtime.worldPatch(viewer,original.agentId(),job.operationId(),job.base().packageId(),job.base().revision(),PROMPT).duplicate())throw new IllegalStateException("PATCH_RETRY_NOT_DEDUPLICATED");
                Files.writeString(root.resolve("admission-checks.json"),JSON.writeValueAsString(Map.of("explicitConsentRequired",true,"exactHashRequired",true,"uiRouteRejected",true,"duplicateDidNotDispatch",true,"baseUnchanged",head.canonicalSha256().equals(original.canonicalSha256()))));checked=true;
            }
            allowApply=job.state().equals("READY")&&approved(root,"approve-world-patch.flag",candidateHash);
            if(!job.state().equals("APPLIED"))return;
            if(!head.canonicalSha256().equals(candidateHash)||runtime.ownedPackage(viewer.getUUID(),head.packageId(),head.revision()).isEmpty())throw new IllegalStateException("WORLD_PATCH_HEAD_NOT_OWNED");
            var activation=world.list(viewer.getUUID()).stream().filter(a->a.packageId().equals(head.packageId())&&a.canonicalSha256().equals(candidateHash)).findFirst().orElse(null);
            allowNative=activation==null&&approved(root,"approve-native.flag",candidateHash);
            if(activation==null)return;
            if(!Set.of("ACTIVE","DISABLED").contains(activation.state()))throw new IllegalStateException("WORLD_PATCH_NATIVE_"+activation.state()+"_"+activation.error());
            if(verifyOnly()){
                if(!activation.state().equals("DISABLED")||world.active(activation.instanceId()))throw new IllegalStateException("WORLD_PATCH_RESTART_REPLAYED");
                var data=world.instance(activation.instanceId()).orElseThrow();var poses=new ArrayList<RuntimeObjectFreezeProof.Pose>();var ids=new LinkedHashMap<String,UUID>();
                if(restartWait<0)restartWait=server.getTickCount();
                for(var entry:data.state().entrySet())if(entry.getKey().startsWith("_object.")){
                    var part=JSON.readValue(entry.getValue(),WorldContentRuntime.ObjectPart.class);var nativeEntity=server.overworld().getEntity(part.entity());
                    if(!(nativeEntity instanceof RuntimeObjectEntity entity)){if(server.getTickCount()-restartWait>100)throw new IllegalStateException("WORLD_PATCH_RESTART_ENTITY_MISSING");return;}
                    world.objectBundle(entity);poses.add(RuntimeObjectFreezeProof.sample(entity));ids.put(entity.header().part(),entity.getUUID());
                }
                if(poses.size()!=3)throw new IllegalStateException("WORLD_PATCH_RESTART_OBJECT_COUNT");objects=Map.copyOf(ids);
                if(restartTick<0){restartTick=server.getTickCount();frozen=List.copyOf(poses);restartState=Map.copyOf(data.state());Files.writeString(root.resolve("restart-before.json"),JSON.writeValueAsString(Map.of("instance",data,"activation",activation,"head",head,"retainedBase",runtime.worldVersion(head.packageId(),job.base().canonicalSha256()).orElseThrow(),"poses",poses)));}
                if(!RuntimeObjectFreezeProof.sameServer(frozen,poses)||!restartState.equals(data.state()))throw new IllegalStateException("WORLD_PATCH_RESTART_CHANGED");
                if(server.getTickCount()-restartTick>=70){done=true;Files.writeString(root.resolve("restart-final.json"),JSON.writeValueAsString(Map.of("instance",data,"activation",activation,"poses",poses,"actualNativeCount",3,"creationReplayed",false,"scriptRunning",world.active(data.instanceId()),"providerCalls",0,"patchJobRevision",job.revision())));}
                return;
            }
            if(!active&&world.verifiedObjects(activation.instanceId())==3){
                instance=activation.instanceId();activationId=activation.operationId();var observations=new ArrayList<WorldObjectSmokeRoles.Part>();
                for(var e:world.instance(instance).orElseThrow().state().entrySet())if(e.getKey().startsWith("_object.")){
                    var part=JSON.readValue(e.getValue(),WorldContentRuntime.ObjectPart.class);var entity=(RuntimeObjectEntity)server.overworld().getEntity(part.entity());if(entity==null)throw new IllegalStateException("WORLD_PATCH_OBJECT_NOT_LOADED");observations.add(new WorldObjectSmokeRoles.Part(part.entity(),entity.header().part(),entity.header().physics().dynamic(),part.dx()));
                }
                var roles=WorldObjectSmokeRoles.resolve(observations);var ids=new LinkedHashMap<String,UUID>();roles.forEach((role,part)->ids.put(role,part.entityId()));objects=Map.copyOf(ids);active=true;priorY=((RuntimeObjectEntity)server.overworld().getEntity(objects.get("right"))).getY();
                Files.writeString(root.resolve("native-active.json"),JSON.writeValueAsString(Map.of("activation",activation,"instance",world.instance(instance).orElseThrow(),"roles",roles,"verifiedObjects",3,"verifiedBlocks",world.verifiedBlocks(instance))));
            }
            if(!active)return;
            var data=world.instance(instance).orElseThrow();long pulse=Long.parseLong(data.state().getOrDefault("pulse","0"));
            if(closedGui&&!independent){if(closeTick==0){closeTick=server.getTickCount();pulseBefore=pulse;}if(server.getTickCount()-closeTick>=80){if(pulse<=pulseBefore||world.verifiedObjects(instance)!=3)throw new IllegalStateException("WORLD_PATCH_BROWSER_DEPENDENCY");independent=true;Files.writeString(root.resolve("without-browser.json"),JSON.writeValueAsString(Map.of("before",pulseBefore,"after",pulse,"instance",data)));}}
            if(!interacted&&!disabled){var right=(RuntimeObjectEntity)server.overworld().getEntity(objects.get("right"));int clicks=Integer.parseInt(data.state().getOrDefault("clicks","0"));if(clicks==0)priorY=right.getY();else{if(clicks!=1)throw new IllegalStateException("WORLD_PATCH_DUPLICATE_INTERACTION");if(clickTick<0){clickTick=server.getTickCount();startY=priorY;maxY=right.getY();}maxY=Math.max(maxY,right.getY());if(server.getTickCount()-clickTick>=20){if(maxY-startY<.05)throw new IllegalStateException("WORLD_PATCH_NO_NATIVE_IMPULSE");interacted=true;Files.writeString(root.resolve("interaction.json"),JSON.writeValueAsString(Map.of("clicks",clicks,"startY",startY,"maxY",maxY,"right",right.getUUID())));}}}
            if(activation.state().equals("DISABLED")){
                var poses=objects.values().stream().map(id->RuntimeObjectFreezeProof.sample((RuntimeObjectEntity)server.overworld().getEntity(id))).toList();
                if(!disabled){disabled=true;disableTick=server.getTickCount();pulseBefore=pulse;frozen=poses;}
                if(pulse!=pulseBefore||!RuntimeObjectFreezeProof.sameServer(frozen,poses))throw new IllegalStateException("WORLD_PATCH_DISABLED_MOVED");
                if(server.getTickCount()-disableTick>=60){done=true;Files.writeString(root.resolve("final.json"),JSON.writeValueAsString(Map.of("patch",job,"activation",activation,"instance",data,"nativeFrozen",true,"originalGenerationReplayed",false,"fullV1",false)));}
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
    private static boolean approved(Path root,String file,String hash)throws Exception{return Files.isRegularFile(root.resolve(file))&&Files.readString(root.resolve(file)).strip().equals(hash);}
}
