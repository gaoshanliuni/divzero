package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.api.packages.RuntimeInstanceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;

/** Explicit Planner/Coder/native-world fixture. No object recipe/template is provided to the production generator. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldPackageTaskSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static final String PROMPT=objectsMode()?"用create_world_package生成悬浮棱镜摆，不用网页或原版方块拼造：3个Native物件，base为细柱与偏置三角顶罩；left/right为不同形状彩色挂饰，在架外用spring弹性悬挂。至少一个模型用vertices/triangles。点击base让right弹起并递增clicks；每20ticks递增pulse。发布后await_world_activation(minimum_objects=3)等我确认，实际3物件验证后finish_task(world_instance)。":"请用 create_world_package 生成新的世界结构包：8个受管原版方块构成不对称青金石/铜柱/白玻璃风向标，保留空隙。只生成一次，禁止网页或直接 place_block 冒充。用 content.placeBlock，脚本每20 ticks递增持久 pulse。发布后检查并 await_world_activation 等待我在内容面板确认，minimum_blocks=8；实例和8块均验证后才 finish_task(world_instance)。";
    public static volatile String agentId,packageId,taskId,failure;public static volatile long packageRevision;public static volatile RuntimeInstanceLocation location;
    public static volatile boolean allowActivation,waiting,completed,uiClosed,independent,disabled,done;
    private static boolean setup,exported,prematureChecked;private static UUID generationOperation,activationOperation,instanceId;private static long pulseBefore,closedTick,doneTick;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public static boolean objectsMode(){return Boolean.getBoolean("mineagent.worldPackageObjectSmoke");}
    public static volatile java.util.Map<String,UUID> objectIds=Map.of();
    public static volatile boolean objectInteractionVerified;
    private static double lastRightY,interactionStartY,maxRightY;private static int interactionTick=-1;
    public static String directory(){return "world-package-task-evidence/"+RUN;}
    public static boolean resume(){return !System.getProperty("mineagent.worldPackageTaskResume","").isBlank();}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldPackageTaskSmoke")||done||failure!=null)return;
        var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        Path root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);var bodies=MineAgentRuntimeServices.bodies(server);var tasks=MineAgentRuntimeServices.tasks(server);var actions=MineAgentRuntimeServices.taskExecutor(server).worldActions();var packages=ServerPackageRuntime.get(server);var world=WorldContentRuntime.get(server);
        try{
            if(!setup){
                if(resume()){
                    var previous=server.getServerDirectory().resolve("world-package-task-evidence").resolve(UUID.fromString(System.getProperty("mineagent.worldPackageTaskResume")).toString());
                    agentId=JSON.readTree(previous.resolve("fixture.json").toFile()).path("agentId").asText();taskId=JSON.readTree(previous.resolve("task.json").toFile()).path("taskId").asText();
                }
                int x=420;var used=world.list(viewer.getUUID()).stream().map(a->(int)a.location().x()).toList();while(used.contains(x))x+=24;
                location=new RuntimeInstanceLocation("minecraft:overworld",x,180,0,0,0);
                for(int dx=-4;dx<=10;dx++)for(int z=-4;z<=8;z++){server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x+dx,179,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());}
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),x+8,185,12,Set.of(),145,18,true);
                viewer.getInventory().setItem(8,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,7));
                if(!resume()){var definition=bodies.createPersistentAt("World Tool "+RUN.substring(0,6),viewer.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(x-2,180,2));agentId=definition.agentId().toString();}
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));grants.addAll(Set.of(dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE,dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES,dev.mineagent.runtime.api.permission.PermissionAction.START_TASK));MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),grants);
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("run",RUN,"agentId",agentId,"prompt",PROMPT,"location",location,"resumedExistingTask",resume(),"approvalGate","Create approve-native.flag only after inspecting exported package source")));setup=true;
            }
            var task=tasks.all().stream().filter(t->t.agentId().toString().equals(agentId)&&t.title().equals(PROMPT)).findFirst().orElse(null);if(task==null)return;taskId=task.taskId().toString();
            var batches=actions.list(viewer.getUUID()).stream().filter(b->b.taskId().equals(task.taskId())).toList();
            Files.writeString(root.resolve("task.json"),JSON.writeValueAsString(task));Files.writeString(root.resolve("rounds.json"),JSON.writeValueAsString(batches));
            if(resume()&&task.status()==TaskStatus.PAUSED)return;
            if(task.status()==TaskStatus.PAUSED||task.status()==TaskStatus.FAILED||batches.stream().anyMatch(b->Set.of("FAILED","INTERRUPTED").contains(b.state())))throw new IllegalStateException("WORLD_TOOL_TASK_FAILED");
            var child=packages.list(viewer.getUUID()).stream().filter(j->j.agentId().toString().equals(agentId)&&j.purpose().equals("WORLD_CONTENT")).findFirst().orElse(null);
            if(child==null){if(task.status()==TaskStatus.COMPLETED)throw new IllegalStateException("WORLD_TOOL_NOT_SELECTED");return;}
            generationOperation=child.operationId();Files.writeString(root.resolve("generation.json"),JSON.writeValueAsString(child));
            if(!child.state().equals("PUBLISHED"))return;
            var pack=packages.worldLibrary().get(child.packageId()).orElseThrow();packageId=pack.packageId().toString();packageRevision=pack.revision();
            if(!exported){
                Files.writeString(root.resolve("package.json"),JSON.writeValueAsString(pack));Files.write(root.resolve("model-output.json"),packages.worldContent().read(child.rawOutputSha256()));
                Path target=root.resolve("package").toAbsolutePath().normalize();for(var resource:pack.resources().values()){Path p=target.resolve(resource.path()).normalize();if(!p.startsWith(target))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(p.getParent());Files.write(p,packages.worldContent().read(resource.sha256()));}exported=true;
            }
            var activation=world.list(viewer.getUUID()).stream().filter(a->a.packageId().equals(pack.packageId())&&a.state().equals("ACTIVE")).findFirst().orElse(null);
            if(activation==null&&!completed){
                if(task.status()==TaskStatus.COMPLETED)throw new IllegalStateException("PUBLICATION_COMPLETED_WORLD_TASK");
                if(!prematureChecked&&batches.stream().allMatch(b->b.state().equals("COMPLETED"))){
                    try{actions.finishTask(task,"{\"checks\":[{\"kind\":\"food\",\"minimum\":0}]}");throw new IllegalStateException("PACKAGE_ONLY_FINISH_ACCEPTED");}
                    catch(IllegalStateException expected){if(!expected.getMessage().equals("WORLD_GOAL_PACKAGE_NOT_ACTIVATED"))throw expected;}
                    prematureChecked=true;Files.writeString(root.resolve("publication-not-completion.json"),JSON.writeValueAsString(Map.of("task",tasks.get(task.taskId()).orElseThrow(),"error","WORLD_GOAL_PACKAGE_NOT_ACTIVATED")));
                }
                waiting=batches.stream().anyMatch(b->b.state().equals("EXECUTING")&&b.actions().get(b.cursor()).tool().equals("await_world_activation"));
                allowActivation=waiting&&Files.exists(root.resolve("approve-native.flag"))&&Files.readString(root.resolve("approve-native.flag")).strip().equals(pack.canonicalSha256());return;
            }
            if(!completed&&task.status()==TaskStatus.COMPLETED){
                if(activation==null||!footprint(world,activation.instanceId())||!world.active(activation.instanceId()))throw new IllegalStateException("WORLD_INSTANCE_NOT_VERIFIED");
                if(!viewer.getInventory().getItem(8).is(net.minecraft.world.item.Items.DIAMOND)||viewer.getInventory().getItem(8).getCount()!=7)throw new IllegalStateException("VIEWER_INVENTORY_CHANGED");
                if(actions.mayProduceWorldPackage(generationOperation))throw new IllegalStateException("TERMINAL_GENERATION_FENCE_OPEN");
                instanceId=activation.instanceId();activationOperation=activation.operationId();completed=true;
                if(objectsMode()){
                    var observed=new ArrayList<WorldObjectSmokeRoles.Part>();
                    for(var entry:world.instance(instanceId).orElseThrow().state().entrySet())if(entry.getKey().startsWith("_object.")){
                        String partKey=entry.getKey().substring("_object.".length());var record=JSON.readValue(entry.getValue(),WorldContentRuntime.ObjectPart.class);
                        if(!(server.overworld().getEntity(record.entity()) instanceof dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity object)||object.header()==null||!object.header().instance().equals(instanceId)||!object.header().part().equals(partKey))throw new IllegalStateException("GENERATED_OBJECT_ROLE_NOT_LOADED");
                        observed.add(new WorldObjectSmokeRoles.Part(record.entity(),partKey,object.header().physics().dynamic(),record.dx()));
                    }
                    var roles=WorldObjectSmokeRoles.resolve(observed);var ids=new LinkedHashMap<String,UUID>();roles.forEach((role,part)->ids.put(role,part.entityId()));objectIds=Map.copyOf(ids);
                    Files.writeString(root.resolve("object-roles.json"),JSON.writeValueAsString(roles));
                    viewer.teleportTo(server.overworld(),location.x(),location.y()+1,location.z()+4,Set.of(),180,10,true);lastRightY=((dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity)server.overworld().getEntity(ids.get("right"))).getY();
                }
                Files.writeString(root.resolve("completed.json"),JSON.writeValueAsString(Map.of("task",task,"activation",activation,"instance",world.instance(instanceId).orElseThrow(),"verifiedBlocks",world.verifiedBlocks(instanceId),"verifiedObjects",world.verifiedObjects(instanceId),"planningCalls",actions.planningAttempts(task),"generationCalls",resume()?0:1,"viewerDiamonds",7,"terminalGenerationFence",false)));
            }
            if(completed&&uiClosed&&!independent){
                var instance=world.instance(instanceId).orElseThrow();long pulse=Long.parseLong(instance.state().getOrDefault("pulse","0"));
                if(closedTick==0){closedTick=server.getTickCount();pulseBefore=pulse;}
                if(server.getTickCount()-closedTick>=80){if(pulse<=pulseBefore||!footprint(world,instanceId))throw new IllegalStateException("WORLD_DEPENDS_ON_BROWSER");independent=true;Files.writeString(root.resolve("without-browser.json"),JSON.writeValueAsString(Map.of("pulseBefore",pulseBefore,"pulseAfter",pulse,"instance",instance,"verifiedBlocks",world.verifiedBlocks(instanceId),"verifiedObjects",world.verifiedObjects(instanceId))));}
            }
            if(objectsMode()&&completed&&!disabled){
                var right=(dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity)server.overworld().getEntity(objectIds.get("right"));if(right==null)throw new IllegalStateException("GENERATED_OBJECT_DISAPPEARED");
                var i=world.instance(instanceId).orElseThrow();int clicks=Integer.parseInt(i.state().getOrDefault("clicks","0"));
                if(clicks==0)lastRightY=right.getY();else{
                    if(clicks!=1)throw new IllegalStateException("GENERATED_INTERACTION_DUPLICATED");
                    if(interactionTick<0){interactionTick=server.getTickCount();interactionStartY=lastRightY;maxRightY=right.getY();}maxRightY=Math.max(maxRightY,right.getY());
                    if(server.getTickCount()-interactionTick>=20&&!objectInteractionVerified){if(maxRightY-interactionStartY<0.05)throw new IllegalStateException("GENERATED_INTERACTION_NO_NATIVE_MOTION");objectInteractionVerified=true;Files.writeString(root.resolve("object-interaction.json"),JSON.writeValueAsString(Map.of("clicks",clicks,"startY",interactionStartY,"maxY",maxRightY,"rightEntity",right.getUUID(),"instance",i)));}
                }
            }
            if(independent&&!disabled){var a=world.list(viewer.getUUID()).stream().filter(v->v.operationId().equals(activationOperation)).findFirst().orElseThrow();if(a.state().equals("DISABLED")){disabled=true;doneTick=server.getTickCount();pulseBefore=Long.parseLong(world.instance(instanceId).orElseThrow().state().get("pulse"));}}
            if(disabled&&server.getTickCount()-doneTick>=60){if(Long.parseLong(world.instance(instanceId).orElseThrow().state().get("pulse"))!=pulseBefore)throw new IllegalStateException("DISABLED_SCRIPT_STILL_RUNNING");done=true;Files.writeString(root.resolve("final.json"),JSON.writeValueAsString(Map.of("task",tasks.get(task.taskId()).orElseThrow(),"instance",world.instance(instanceId).orElseThrow(),"activationId",activationOperation,"generationOperation",generationOperation,"nativeDisabled",true,"fullV1",false)));}
        }catch(Exception error){failure=error.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("code",failure==null?error.getClass().getSimpleName():failure)));}
    }
    private static boolean footprint(WorldContentRuntime world,UUID id){return objectsMode()?world.verifiedBlocks(id)==0&&world.verifiedObjects(id)==3:world.verifiedBlocks(id)==8;}
}
