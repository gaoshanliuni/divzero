package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Real GUI commands are verified against Native registry, bodies and tasks. Setup is fixture-only. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class AgentManagementSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile UUID agent,peer,otherOwner,sourceTask,newTask;
    public static volatile int step;
    public static volatile boolean sourceRunning,done;
    public static volatile String failure="";
    public static final Set<String> clientSeen=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static ManagedTask paused;private static int started=-1;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.agentManagementSmoke");}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void save(net.minecraft.server.MinecraftServer s,String file,Object data)throws Exception{var root=s.getServerDirectory().resolve("agent-management-evidence");Files.createDirectories(root);Files.writeString(root.resolve(file),JSON.writeValueAsString(data));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||!failure.isEmpty())return;var s=event.getServer();if(started<0)started=s.getTickCount();
        var viewer=s.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        try{require(s.getTickCount()-started<1800,"AGENT_MANAGEMENT_TIMEOUT_"+step);var bodies=MineAgentRuntimeServices.bodies(s);var tm=MineAgentRuntimeServices.tasks(s);var actions=MineAgentRuntimeServices.taskExecutor(s).worldActions();var body=agent==null?null:bodies.body(agent).orElse(null);
            if(step==0){
                var data=s.getWorldData();var field=data.getClass().getDeclaredField("settings");field.setAccessible(true);var settings=(LevelSettings)field.get(data);field.set(data,new LevelSettings(settings.levelName(),settings.gameType(),settings.difficultySettings(),false,settings.dataConfiguration(),settings.lifecycle()));s.getPlayerList().deop(viewer.nameAndId());require(!viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),"VIEWER_NOT_REGULAR");
                MineAgentRuntimeServices.permissions(s).setTrustedActions(viewer.getUUID(),Set.of(PermissionAction.CREATE_AGENT));
                for(int x=917;x<=943;x++)for(int z=-3;z<=12;z++){s.overworld().setBlockAndUpdate(new BlockPos(x,169,z),Blocks.STONE.defaultBlockState());for(int y=170;y<=174;y++)s.overworld().setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
                viewer.setGameMode(GameType.SPECTATOR);viewer.teleportTo(s.overworld(),922.5,173,7.5,Set.of(),180,35,true);otherOwner=UUID.randomUUID();peer=bodies.createAt("其他玩家的 AI",otherOwner,AgentMode.CREATOR,s.overworld(),new Vec3(941,170,7)).agentId();
                save(s,"setup.json",Map.of("viewer",viewer.getUUID(),"operator",false,"peer",peer,"otherOwner",otherOwner,"world",MineAgentRuntimeServices.worldId(s)));step=1;
            }else if(step==1){var a=bodies.definitions().stream().filter(d->d.ownerPlayerId().equals(viewer.getUUID())&&d.displayName().equals("界面伙伴")).findFirst().orElse(null);if(a==null)return;agent=a.agentId();require(bodies.body(agent).isPresent(),"GUI_CREATE_BODY_MISSING");save(s,"created.json",ServerAgentManagement.view(viewer));step=2;}
            else if(step==2&&bodies.definitions().stream().anyMatch(a->a.agentId().equals(agent)&&a.displayName().equals("伙伴改名"))){save(s,"renamed.json",ServerAgentManagement.view(viewer));body.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);step=3;}
            else if(step==3&&clientSeen.contains("spectator-ui")&&body.gameMode.getGameModeForPlayer()==GameType.SURVIVAL){require(body.canAct(),"MODE_NOT_APPLIED");save(s,"mode-applied.json",ServerAgentManagement.view(viewer));step=4;}
            else if(step==4&&bodies.definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElseThrow().collaboratorPlayerIds().contains(otherOwner)){save(s,"collaborator-added.json",ServerAgentManagement.view(viewer));step=5;}
            else if(step==5&&!bodies.definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElseThrow().collaboratorPlayerIds().contains(otherOwner)){
                save(s,"collaborator-removed.json",ServerAgentManagement.view(viewer));body.teleportTo(s.overworld(),922.5,170,2.5,Set.of(),0,0,true);body.resetFallDistance();body.setDeltaMovement(Vec3.ZERO);body.setHealth(20);body.getInventory().clearContent();body.getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));s.overworld().setBlockAndUpdate(new BlockPos(924,170,2),Blocks.OBSIDIAN.defaultBlockState());
                var task=tm.create(agent,viewer.getUUID(),"挖掘黑曜石后移动到目标",50,List.of(new TaskStepSpec("plan",Set.of()),new TaskStepSpec("execute",Set.of("plan"))));sourceTask=task.taskId();task=tm.completeStep(task.taskId(),task.revision(),"plan").task();actions.submit(task,List.of(Map.of("id","old-mine","name","break_block","arguments","{\"x\":924,\"y\":170,\"z\":2}")));step=6;
            }else if(step==6){
                if(body.miningTarget().isPresent()&&!sourceRunning){sourceRunning=true;save(s,"old-action-started.json",actions.list(viewer.getUUID()));}
                var task=tm.get(sourceTask).orElseThrow();if(task.status()!=TaskStatus.PAUSED)return;require(clientSeen.contains("pause-sent")&&body.isAlive(),"SOURCE_INTERRUPTED_BEFORE_GUI_PAUSE");paused=task;require(body.miningTarget().isEmpty()&&s.overworld().getBlockState(new BlockPos(924,170,2)).is(Blocks.OBSIDIAN),"PAUSE_NOT_NATIVE");save(s,"source-paused.json",Map.of("task",task,"actions",actions.list(viewer.getUUID()),"body",dev.mineagent.runtime.neoforge.body.BodySurvivalSmokeServer.snapshot(body)));step=7;
            }else if(step==7){for(var task:tm.all()){var source=tm.replanSource(task.taskId());if(source.isPresent()&&source.get().original().taskId().equals(sourceTask)){newTask=task.taskId();save(s,"replan-created.json",Map.of("source",source.get(),"task",task));step=8;break;}}}
            else if(step==8){var task=tm.get(newTask).orElseThrow();if(task.status()!=TaskStatus.COMPLETED)return;require(tm.get(sourceTask).orElseThrow().equals(paused),"REPLAN_CHANGED_OLD_TASK");require(body.position().distanceToSqr(930.5,170,5.5)<1&&s.overworld().getBlockState(new BlockPos(924,170,2)).is(Blocks.OBSIDIAN),"NEW_PLAN_NATIVE_RESULT");save(s,"new-task-completed.json",Map.of("source",paused,"newTask",task,"actions",actions.list(viewer.getUUID()),"body",dev.mineagent.runtime.neoforge.body.BodySurvivalSmokeServer.snapshot(body)));step=9;}
            else if(step==9&&bodies.body(agent).isEmpty()){require(bodies.definitions().stream().noneMatch(a->a.agentId().equals(agent))&&s.getPlayerList().getPlayer(agent)==null,"DELETE_NOT_APPLIED");save(s,"deleted.json",ServerAgentManagement.view(viewer));step=10;}
            else if(step==10&&clientSeen.containsAll(Set.of("peer-rejected","positive-create-replay","replan-duplicate","deleted-create-replay"))){
                require(bodies.definitions().size()==1&&bodies.definitions().getFirst().agentId().equals(peer),"CREATE_REPLAY_RESURRECTED");require(tm.all().stream().filter(t->tmSafeParent(tm,t.taskId(),sourceTask)).count()==1,"REPLAN_DUPLICATE_TASK");
                save(s,"result.json",Map.of("status","NATIVE_WEBGUI_AGENT_MANAGEMENT_AND_FRESH_REPLAN_VERIFIED","agent",agent,"sourceTask",sourceTask,"newTask",newTask,"checks",clientSeen,"paidModelCalls",0,"systemInputInjected",false,"fullV1",false));bodies.remove(peer,otherOwner,true);done=true;
            }
        }catch(Exception e){failure=e.toString();save(s,"failure.json",Map.of("step",step,"error",failure));}
    }
    private static boolean tmSafeParent(dev.mineagent.runtime.core.task.TaskManager tm,UUID task,UUID parent){try{return tm.replanSource(task).map(r->r.original().taskId().equals(parent)).orElse(false);}catch(Exception e){throw new IllegalStateException(e);}}
}
