package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.core.task.TaskAuthorityFence;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real non-OP client, Worker and Native world; local controlled HTTP responses are explicitly not a live model. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class TaskAuthoritySmokeServer {
    public static final String PRE="Authority pre-dispatch",QUESTION="Authority waiting question",LATE="Authority in-flight",QUEUED="Authority queued request",POSITIVE="Authority newly authorized";
    public static final BlockPos TARGET=new BlockPos(5,80,4);
    private static final ObjectMapper JSON=new ObjectMapper();private static final AtomicInteger httpRequests=new AtomicInteger();private static final CountDownLatch release=new CountDownLatch(1);
    private static com.sun.net.httpserver.HttpServer endpoint;private static UUID viewerId,ownerId,agent,ownAgent,preTask,questionTask,decision,lateTask,queuedTask,positiveTask;
    private static int phase,ticks,finishAt=-1;private static boolean preDone,questionDone,replayChecked;private static String failure="";
    public static boolean enabled(){return Boolean.getBoolean("mineagent.taskAuthorityServer");}
    private static Path root(net.minecraft.server.MinecraftServer server){return server.getServerDirectory().resolve("task-authority-evidence");}
    private static void write(net.minecraft.server.MinecraftServer server,String name,Object value)throws Exception{Files.createDirectories(root(server));Files.writeString(root(server).resolve(name),JSON.writeValueAsString(value));}
    private static void grant(net.minecraft.server.MinecraftServer server,boolean enabled){if(!MineAgentRuntimeServices.bodies(server).setCollaborator(agent,ownerId,viewerId,enabled))throw new IllegalStateException("FIXTURE_COLLABORATOR_CHANGE_FAILED");}
    private static ManagedTask find(net.minecraft.server.MinecraftServer server,String title){return MineAgentRuntimeServices.tasks(server).all().stream().filter(t->t.ownerPlayerId().equals(viewerId)&&t.agentId().equals(agent)&&t.title().equals(title)).findFirst().orElse(null);}
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    private static void mockProvider(net.minecraft.server.MinecraftServer server)throws Exception{
        endpoint=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        endpoint.setExecutor(Executors.newCachedThreadPool(r->Thread.ofPlatform().daemon().unstarted(r)));
        endpoint.createContext("/v1/chat/completions",exchange->{try{
            exchange.getRequestBody().readNBytes(1_048_576);int n=httpRequests.incrementAndGet();
            if(n==1&&!release.await(90,TimeUnit.SECONDS))throw new IllegalStateException("FIXTURE_RELEASE_TIMEOUT");
            String name=n<3?"place_block":"finish_task";
            Object args=n<3?Map.of("x",TARGET.getX(),"y",TARGET.getY(),"z",TARGET.getZ(),"block","minecraft:stone"):Map.of("checks",List.of(Map.of("kind","block","x",TARGET.getX(),"y",TARGET.getY(),"z",TARGET.getZ(),"block","minecraft:stone")));
            var call=Map.of("id","controlled-"+n,"type","function","function",Map.of("name",name,"arguments",JSON.writeValueAsString(args)));
            byte[] bytes=JSON.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","Controlled fixture, not a model response","tool_calls",List.of(call))))));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});
        endpoint.start();var config=MineAgentRuntimeServices.config(server);
        require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+endpoint.getAddress().getPort()+"/v1/","provider.openai.model","controlled-authority-fixture","provider.openai.apiKey","local-fixture-token","voice.output.enabled","false")),true).accepted(),"MOCK_CONFIG_FAILED");
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled())return;var server=event.getServer();ticks++;
        if(finishAt>=0){if(ticks-finishAt>=40){release.countDown();if(endpoint!=null)endpoint.stop(0);MineAgentRuntimeMod.LOGGER.info(failure.isEmpty()?"MINEAGENT_TASK_AUTHORITY_SERVER_OK":"MINEAGENT_TASK_AUTHORITY_SERVER_FAILED");server.halt(false);}return;}
        if(!failure.isEmpty())return;
        var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)&&p.nameAndId().name().equals("AuthUser")).findFirst().orElse(null);if(viewer==null)return;
        try{
            require(!viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),"FIXTURE_VIEWER_MUST_NOT_BE_OP");
            var bodies=MineAgentRuntimeServices.bodies(server);var tasks=MineAgentRuntimeServices.tasks(server);var executor=MineAgentRuntimeServices.taskExecutor(server);var actions=executor.worldActions();var decisions=MineAgentRuntimeServices.decisions(server);
            if(agent==null){
                viewerId=viewer.getUUID();ownerId=UUID.randomUUID();for(int x=-2;x<=9;x++)for(int z=1;z<=8;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());server.overworld().setBlockAndUpdate(TARGET,Blocks.AIR.defaultBlockState());
                viewer.teleportTo(server.overworld(),0.5,80,5.5,Set.of(),-90,10,true);
                ownAgent=bodies.createPersistentAt("Authority owned",viewerId,server.overworld(),new net.minecraft.world.phys.Vec3(2.5,80,6.5)).agentId();
                agent=bodies.createPersistentAt("Authority shared",ownerId,server.overworld(),new net.minecraft.world.phys.Vec3(4.5,80,4.5)).agentId();grant(server,true);mockProvider(server);
                write(server,"fixture.json",Map.of("viewer",viewerId,"operator",false,"agent",agent,"owner",ownerId,"ownAgent",ownAgent,"mode","CONTROLLED_LOOPBACK_PROVIDER_NOT_PAID_MODEL"));
            }
            if(phase==0){var task=find(server,PRE);if(task==null)return;preTask=task.taskId();grant(server,false);var stopped=tasks.get(preTask).orElseThrow();require(TaskAuthorityFence.revoked(stopped)&&actions.planningAttempts(task)==0&&httpRequests.get()==0,"PRE_DISPATCH_REQUEST_WAS_SENT");write(server,"before-provider.json",Map.of("task",stopped,"planningAttempts",0,"httpRequests",0));phase=1;}
            if(phase==1&&preDone){grant(server,true);phase=2;}
            if(phase==2){var task=find(server,QUESTION);if(task==null)return;questionTask=task.taskId();
                executor.askPlayer(task,List.of(Map.of("id","controlled-question","name","ask_player","arguments","{\"title\":\"撤权时保留草稿\",\"question\":\"这是受控问题，不是模型生成。请保留未提交内容。\",\"options\":[{\"id\":\"a\",\"title\":\"方案 A\",\"description\":\"保留人工选择\"}],\"selection_mode\":\"SINGLE\",\"min_selections\":1,\"max_selections\":1}")));
                decision=decisions.allFor(viewerId).stream().filter(q->decisions.taskLink(q.decisionId()).filter(l->l.taskId().equals(questionTask)).isPresent()).findFirst().orElseThrow().decisionId();phase=3;
            }
            if(phase==4&&questionDone){require(TaskAuthorityFence.revoked(tasks.get(questionTask).orElseThrow())&&decisions.get(decision).orElseThrow().status()==DecisionStatus.SUPERSEDED,"WAITING_QUESTION_NOT_INVALIDATED");write(server,"waiting-revoked.json",Map.of("task",tasks.get(questionTask).orElseThrow(),"question",decisions.get(decision).orElseThrow(),"httpRequests",httpRequests.get()));grant(server,true);phase=5;}
            if(phase==5){var task=find(server,LATE);if(task==null)return;lateTask=task.taskId();if(httpRequests.get()==0)return;require(httpRequests.get()==1&&executor.isPlanning(lateTask),"LATE_REQUEST_NOT_HELD");grant(server,false);grant(server,true);write(server,"late-revoked-and-regranted.json",Map.of("before",task,"after",tasks.get(lateTask).orElseThrow(),"httpRequests",1));phase=6;}
            if(phase==6){var task=find(server,QUEUED);if(task==null)return;queuedTask=task.taskId();if(!executor.isPlanning(queuedTask))return;require(httpRequests.get()==1,"QUEUED_REQUEST_NOT_HELD");grant(server,false);grant(server,true);write(server,"queued-revoked.json",Map.of("task",tasks.get(queuedTask).orElseThrow(),"httpRequests",1));release.countDown();phase=7;}
            if(phase==7){if(executor.isPlanning(lateTask)||executor.isPlanning(queuedTask))return;require(httpRequests.get()==1,"REVOKED_QUEUED_PROVIDER_REQUEST_SENT");require(server.overworld().getBlockState(TARGET).isAir(),"LATE_RESULT_MUTATED_WORLD");require(actions.list(viewerId).stream().noneMatch(b->b.taskId().equals(lateTask)||b.taskId().equals(queuedTask)),"LATE_RESULT_CREATED_BATCH");write(server,"late-and-queued-discarded.json",Map.of("late",tasks.get(lateTask).orElseThrow(),"queued",tasks.get(queuedTask).orElseThrow(),"httpRequests",1,"nativeBlock","minecraft:air","batches",0));phase=8;}
            if(phase==8&&replayChecked){var task=find(server,POSITIVE);if(task==null)return;positiveTask=task.taskId();if(task.status()==TaskStatus.PAUSED||task.status()==TaskStatus.FAILED)throw new IllegalStateException("AUTHORIZED_NEW_TASK_FAILED");if(task.status()!=TaskStatus.COMPLETED)return;
                require(httpRequests.get()==3&&server.overworld().getBlockState(TARGET).is(Blocks.STONE),"AUTHORIZED_NATIVE_NOT_VERIFIED");for(var id:List.of(preTask,questionTask,lateTask,queuedTask))require(TaskAuthorityFence.revoked(tasks.get(id).orElseThrow()),"OLD_TASK_RESUMED_AFTER_REGRANT");
                write(server,"final.json",Map.of("task",task,"oldTasks",List.of(preTask,questionTask,lateTask,queuedTask).stream().map(id->tasks.get(id).orElseThrow()).toList(),"question",decisions.get(decision).orElseThrow(),"batches",actions.list(viewerId),"localHttpRequests",3,"paidProviderCalls",0,"operator",false,"nativeBlock","minecraft:stone"));phase=9;
            }
            if(ticks>9000)throw new IllegalStateException("AUTHORITY_FIXTURE_TIMEOUT");
        }catch(Exception e){failure=e.getMessage()!=null&&e.getMessage().matches("[A-Z0-9_]{1,100}")?e.getMessage():"AUTHORITY_FIXTURE_FAILED";release.countDown();write(server,"failure.json",Map.of("phase",phase,"error",failure,"httpRequests",httpRequests.get()));}
    }
    public static void handle(ServerPlayer viewer,UiPayloads.Command packet)throws Exception{
        var server=viewer.level().getServer();if(!enabled()||viewerId==null||!viewer.getUUID().equals(viewerId))return;String action=JSON.readTree(packet.json()).path("action").asText();
        if(action.equals("preDone")&&phase==1)preDone=true;
        if(action.equals("draftReady")&&phase==3){grant(server,false);phase=4;}
        if(action.equals("questionDone")&&phase==4)questionDone=true;
        if(action.equals("replayChecked")&&phase==8)replayChecked=true;
        if(action.equals("finish")&&(phase==9||!failure.isEmpty()))finishAt=ticks;
        var info=new LinkedHashMap<String,Object>();info.put("phase",phase);info.put("agent",agent);info.put("decision",decision);info.put("failure",failure);info.put("httpRequests",httpRequests.get());info.put("operator",viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        PacketDistributor.sendToPlayer(viewer,new UiPayloads.Event(packet.requestId(),"taskAuthorityFixture",JSON.writeValueAsString(info)));
    }
}
