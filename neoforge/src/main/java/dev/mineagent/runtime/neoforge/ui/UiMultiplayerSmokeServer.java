package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Two real network clients. Fixture coordination never authorizes a decision or completes its task for a client. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class UiMultiplayerSmokeServer {
    private static final String RUN=UUID.randomUUID().toString();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,Case> cases=new LinkedHashMap<>();
    private static final Map<String,Integer> joins=new LinkedHashMap<>();
    private static boolean prepared,finished;private static int ticks,finishTick,baseX;
    private static final class Case {
        final String role,marker;final UUID owner,agent,decision,task;final BlockPos target;
        int builds;long completedRevision;boolean done;
        Case(String role,UUID owner,UUID agent,UUID decision,UUID task,BlockPos target){this.role=role;this.owner=owner;this.agent=agent;this.decision=decision;this.task=task;this.target=target;marker="PRIVATE_"+role+"_"+RUN.substring(0,8);}
    }
    private static String role(ServerPlayer p){return p.nameAndId().name().equals("UiMultiA")?"A":p.nameAndId().name().equals("UiMultiB")?"B":"";}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.uiMultiplayerServer");}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(enabled()&&e.getEntity() instanceof ServerPlayer p&&!role(p).isEmpty())joins.merge(role(p),1,Integer::sum);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void logout(PlayerEvent.PlayerLoggedOutEvent e)throws Exception{
        if(!enabled()||!(e.getEntity() instanceof ServerPlayer p)||role(p).isEmpty())return;
        var server=p.level().getServer();var sessions=ServerUiRuntime.get(server).sessions().list(p.getUUID());
        if(!sessions.isEmpty())throw new IllegalStateException("DISCONNECT_RETAINED_UI_AUTHORITY");
        write(server,"disconnect-"+role(p)+"-"+joins.getOrDefault(role(p),0)+".json",Map.of("viewer",p.getUUID(),"activeSessions",sessions));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        if(!enabled())return;var server=e.getServer();ticks++;
        try{
            if(!prepared){
                baseX=(MineAgentRuntimeServices.bodies(server).definitions().size()/2)*40;
                for(int x=baseX-5;x<=baseX+25;x++)for(int z=-5;z<=16;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,99,z),Blocks.STONE.defaultBlockState());
                prepared=true;write(server,"ready.json",Map.of("run",RUN,"dedicated",server.isDedicatedServer(),"providerCalls",0));
            }
            var bodies=MineAgentRuntimeServices.bodies(server);var tasks=MineAgentRuntimeServices.tasks(server);var decisions=MineAgentRuntimeServices.decisions(server);
            for(var player:List.copyOf(server.getPlayerList().getPlayers())){
                String role=role(player);if(role.isEmpty()||cases.containsKey(role))continue;
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(server.overworld(),baseX+(role.equals("A")?8.5:12.5),104,role.equals("A")?14.5:16.5,Set.of(),role.equals("A")?174:188,14,true);
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(dev.mineagent.runtime.api.permission.PermissionAction.CHAT);MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
                var agent=bodies.createPersistentAt("Dual UI Agent "+role+" "+RUN.substring(0,6),player.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(baseX+(role.equals("A")?2.5:18.5),100,2.5));
                var task=tasks.create(agent.agentId(),player.getUUID(),"选择后仅执行自己的一次放置 "+role,1,List.of(new TaskStepSpec("apply",Set.of()),new TaskStepSpec("free",Set.of())));
                var c=new Case(role,player.getUUID(),agent.agentId(),UUID.randomUUID(),task.taskId(),new BlockPos(baseX+(role.equals("A")?2:18),100,4));cases.put(role,c);
                decisions.openForTask(new DecisionRequest(c.decision,1,c.owner,task.intentRevision(),DecisionKind.DESIGN,"专属选择 "+role,c.marker,
                        List.of(new DecisionOption("a","方案一","保留"),new DecisionOption("b","方案二","执行自己的步骤")),SelectionMode.SINGLE,1,1,true,DecisionStatus.OPEN),tasks,c.task,Set.of("apply"));
                var current=tasks.get(c.task).orElseThrow();tasks.completeStep(c.task,current.revision(),"free");
            }
            for(var c:cases.values()){
                var task=tasks.get(c.task).orElseThrow();
                if(c.builds==0&&decisions.get(c.decision).orElseThrow().status()==DecisionStatus.RESOLVED&&task.runnableStepIds().contains("apply")){
                    var answer=decisions.acceptedAnswer(c.decision).orElseThrow();
                    if(!answer.selectedOptionIds().equals(List.of("b"))||!answer.customText().equals("专属答复 "+c.role+" · 保留数据"))throw new IllegalStateException("PRIVATE_TASK_ANSWER_MIXED");
                    if(!bodies.placeBlock(c.agent,c.target,Blocks.OAK_PLANKS)||!server.overworld().getBlockState(c.target).is(Blocks.OAK_PLANKS))throw new IllegalStateException("NATIVE_TASK_RESULT_MISSING");
                    c.builds++;var result=tasks.completeStep(c.task,task.revision(),"apply");if(!result.accepted()||result.task().status()!=TaskStatus.COMPLETED)throw new IllegalStateException("TASK_COMPLETION_FAILED");
                    c.completedRevision=result.task().revision();write(server,"accepted-"+c.role+".json",Map.of("decision",decisions.get(c.decision).orElseThrow(),"answer",answer,"task",result.task(),"builds",c.builds,"agent",bodies.definitions().stream().filter(a->a.agentId().equals(c.agent)).findFirst().orElseThrow()));
                }
                if(c.builds>0&&(tasks.get(c.task).orElseThrow().revision()!=c.completedRevision||!server.overworld().getBlockState(c.target).is(Blocks.OAK_PLANKS)))throw new IllegalStateException("COMPLETED_TASK_REPLAYED");
            }
            if(cases.size()==2&&cases.values().stream().allMatch(c->c.done)&&!finished){
                if(joins.getOrDefault("A",0)<3||cases.values().stream().anyMatch(c->c.builds!=1))throw new IllegalStateException("MULTIPLAYER_SEQUENCE_INCOMPLETE");
                write(server,"result.json",Map.of("status","DEDICATED_TWO_CLIENT_UI_VERIFIED","joins",joins,"cases",cases.values().stream().map(c->Map.of("role",c.role,"viewer",c.owner,"agent",c.agent,"decision",c.decision,"task",c.task,"builds",c.builds,"taskRevision",c.completedRevision)).toList(),"providerCalls",0));
                finished=true;finishTick=ticks;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_MULTIPLAYER_SERVER_OK run={}",RUN);
            }
            if(finished&&ticks-finishTick>80)server.halt(false);
            if(ticks>18000)throw new IllegalStateException("UI_MULTIPLAYER_SERVER_TIMEOUT");
        }catch(Exception failure){write(server,"failure.json",Map.of("code",String.valueOf(failure.getMessage())));server.halt(false);throw failure;}
    }
    public static void handle(ServerPlayer player,UiPayloads.Command packet)throws Exception{
        if(!enabled()||role(player).isEmpty())throw new SecurityException("FIXTURE_SOURCE");
        var c=cases.get(role(player));var server=player.level().getServer();
        var args=JSON.readTree(packet.json());if(args.path("action").asText().equals("done")){if(c==null||c.builds!=1)throw new IllegalStateException("FIXTURE_NOT_VERIFIED");c.done=true;}
        Map<String,?> value;
        if(cases.size()!=2||c==null)value=Map.of("ready",false,"run",RUN);
        else{var other=cases.get(c.role.equals("A")?"B":"A");value=Map.ofEntries(Map.entry("ready",true),Map.entry("run",RUN),Map.entry("role",c.role),Map.entry("decisionId",c.decision),Map.entry("foreignDecisionId",other.decision),Map.entry("marker",c.marker),Map.entry("agentIds",cases.values().stream().map(v->v.agent).toList()),Map.entry("builds",c.builds),Map.entry("aJoins",joins.getOrDefault("A",0)),Map.entry("aDone",cases.get("A").done),Map.entry("aOnline",server.getPlayerList().getPlayer(cases.get("A").owner)!=null));}
        PacketDistributor.sendToPlayer(player,new UiPayloads.Event(packet.requestId(),"uiMultiFixture",JSON.writeValueAsString(value)));
    }
    private static void write(net.minecraft.server.MinecraftServer server,String name,Object value)throws Exception{Path root=server.getServerDirectory().resolve("ui-multiplayer-evidence").resolve(RUN);Files.createDirectories(root);Files.writeString(root.resolve(name),JSON.writeValueAsString(value));}
}
