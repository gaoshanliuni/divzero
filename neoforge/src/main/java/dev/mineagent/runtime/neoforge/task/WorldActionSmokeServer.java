package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.api.task.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldActionSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static final String PROMPT="先走到 (325,170,1)，用自己的铁镐挖掉 (327,170,1) 的石头，再走到 (327,170,1) 拾取掉落，然后在 (328,170,1) 放一块 minecraft:cobblestone。严格按此顺序调用 move_to、break_block、move_to、place_block，不说话，不使用网页工具，不借用观察玩家物品。";
    public static volatile String agentId,cancelTaskId,failure;public static volatile boolean finished,liveVerified;private static UUID agent,taskId;private static boolean setup,movedViewer,earlyStop;private static int phase,phaseTick;private static long started=System.currentTimeMillis();private static net.minecraft.world.item.ItemStack viewerSlot;private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static WorldActionJournal.Batch[] replay;private static int replayCursor,replayWaitAt;
    public static String directory(){return "world-action-evidence/"+RUN;}
    private static List<Map<String,Object>> calls(String first,String second)throws Exception{var result=new ArrayList<Map<String,Object>>();result.add(Map.of("id","one","name",first,"arguments",first.equals("move_to")?"{\"x\":334,\"y\":170,\"z\":1}":"{\"x\":324,\"y\":170,\"z\":1}"));if(second!=null)result.add(Map.of("id","two","name","place_block","arguments","{\"x\":328,\"y\":170,\"z\":2,\"block\":\"minecraft:cobblestone\"}"));return result;}
    @SubscribeEvent public static void cancelNativeBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event){if(Boolean.getBoolean("mineagent.worldActionSmoke")&&phase==3&&event.getPlayer() instanceof MineAgentPlayer p&&p.agentId().equals(agent)&&event.getPos().equals(new BlockPos(324,170,1)))event.setCanceled(true);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldActionSmoke")||finished||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);var tasks=MineAgentRuntimeServices.tasks(server);var actions=MineAgentRuntimeServices.taskExecutor(server).worldActions();var bodies=MineAgentRuntimeServices.bodies(server);
        try{
            if(!System.getProperty("mineagent.worldActionRestart","").isBlank()){
                var old=server.getServerDirectory().resolve("world-action-evidence").resolve(UUID.fromString(System.getProperty("mineagent.worldActionRestart")).toString()).resolve("restart-checkpoint.json");var checkpoint=JSON.readTree(old.toFile());var id=UUID.fromString(checkpoint.path("taskId").asText());
                var task=tasks.get(id).orElseThrow();var batch=actions.list(viewer.getUUID()).stream().filter(b->b.taskId().equals(id)).findFirst().orElseThrow();
                if(task.status()!=TaskStatus.PAUSED)return;
                if(!batch.state().equals("INTERRUPTED")||!batch.error().equals("SERVER_RESTARTED_NO_REPLAY")||!server.overworld().getBlockState(new BlockPos(324,170,1)).is(Blocks.OBSIDIAN))throw new IllegalStateException("WORLD_ACTION_RESTART_REPLAYED");
                Files.writeString(root.resolve("restart-verified.json"),JSON.writeValueAsString(Map.of("task",task,"batch",batch,"block","minecraft:obsidian","mode","NO_PROVIDER_NO_ACTION_REPLAY")));finished=true;return;
            }
            if(!setup){
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.teleportTo(server.overworld(),322.5,172,4.5,Set.of(),150,20,true);viewerSlot=viewer.getInventory().getItem(8).copy();viewer.getInventory().setItem(8,new ItemStack(Items.DIAMOND,7));
                for(int x=319;x<=338;x++)for(int z=-2;z<=5;z++){server.overworld().setBlockAndUpdate(new BlockPos(x,169,z),Blocks.STONE.defaultBlockState());for(int y=170;y<=173;y++)server.overworld().setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
                server.overworld().setBlockAndUpdate(new BlockPos(327,170,1),Blocks.STONE.defaultBlockState());
                var def=bodies.createAt("Action Fixture "+RUN.substring(0,6),viewer.getUUID(),dev.mineagent.runtime.api.agent.AgentMode.SURVIVAL,server.overworld(),new net.minecraft.world.phys.Vec3(322.5,170,1.5));agent=def.agentId();agentId=agent.toString();var body=bodies.body(agent).orElseThrow();body.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));body.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,2));body.getInventory().setSelectedSlot(0);setup=true;phaseTick=server.getTickCount();
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("agentId",agent,"actorId",body.getUUID(),"viewerId",viewer.getUUID(),"prompt",PROMPT,"planReplay",System.getProperty("mineagent.worldActionPlan",""))));
                if(!System.getProperty("mineagent.worldActionPlan","").isBlank()){
                    var old=server.getServerDirectory().resolve("world-action-evidence").resolve(UUID.fromString(System.getProperty("mineagent.worldActionPlan")).toString()).resolve("model-rounds.json");
                    replay=JSON.readValue(old.toFile(),WorldActionJournal.Batch[].class);var t=tasks.create(agent,viewer.getUUID(),PROMPT,50,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));taskId=t.taskId();t=tasks.completeStep(t.taskId(),t.revision(),"fixture_plan").task();
                    replayNext(actions,t);
                }
            }
            var body=bodies.body(agent).orElseThrow();
            if(taskId==null){var found=tasks.all().stream().filter(t->t.agentId().equals(agent)&&t.title().equals(PROMPT)&&t.updatedAtEpochMillis()>=started).findFirst().orElse(null);if(found==null)return;taskId=found.taskId();}
            var task=tasks.get(taskId).orElseThrow();var batch=actions.list(viewer.getUUID()).stream().filter(b->b.taskId().equals(taskId)).max(java.util.Comparator.comparingInt(WorldActionJournal.Batch::round)).orElse(null);
            if(batch==null){if(task.status()==TaskStatus.PAUSED)throw new IllegalStateException("PLANNING_FAILED");return;}
            if(phase==0){
                Files.writeString(root.resolve("model-batch.json"),JSON.writeValueAsString(batch));
                Files.writeString(root.resolve("model-rounds.json"),JSON.writeValueAsString(actions.list(viewer.getUUID()).stream().filter(b->b.taskId().equals(taskId)).toList()));
                if(!movedViewer&&batch.state().equals("EXECUTING")){movedViewer=true;viewer.teleportTo(server.overworld(),333,174,10,Set.of(),130,20,true);}
                if(batch.state().equals("EXECUTING")&&batch.cursor()==0&&task.status()==TaskStatus.COMPLETED)throw new IllegalStateException("MOVE_DISPATCH_COMPLETED_TASK");
                if(Set.of("FAILED","INTERRUPTED").contains(batch.state()))throw new IllegalStateException("LIVE_"+batch.error());
                if(task.status()==TaskStatus.PAUSED)throw new IllegalStateException("LIVE_PLANNING_PAUSED");
                if(batch.state().equals("COMPLETED")&&task.status()==TaskStatus.RUNNING&&replay!=null){
                    if(replayWaitAt==0){replayWaitAt=server.getTickCount();body.claimTaskControl(task.taskId(),()->true,()->{});body.movementController().stop();}
                    if(server.getTickCount()-replayWaitAt<30)return;body.releaseTaskControl(task.taskId());replayWaitAt=0;
                    if(replayCursor<replay.length){replayNext(actions,task);return;}
                    actions.finishTask(task,"{\"checks\":[{\"kind\":\"block\",\"x\":327,\"y\":170,\"z\":1,\"block\":\"minecraft:air\"},{\"kind\":\"block\",\"x\":328,\"y\":170,\"z\":1,\"block\":\"minecraft:cobblestone\"},{\"kind\":\"inventory\",\"item\":\"minecraft:cobblestone\",\"count\":2}]}");return;
                }
                if(!batch.state().equals("COMPLETED")||task.status()!=TaskStatus.COMPLETED)return;
                int cobble=count(body,Items.COBBLESTONE);var pick=body.getInventory().getItem(0);
                if(task.status()!=TaskStatus.COMPLETED||!server.overworld().getBlockState(new BlockPos(327,170,1)).isAir()||!server.overworld().getBlockState(new BlockPos(328,170,1)).is(Blocks.COBBLESTONE)||cobble!=2||pick.getDamageValue()!=1||viewer.getInventory().getItem(8).getCount()!=7)throw new IllegalStateException("LIVE_NATIVE_RESULT_MISMATCH");
                Files.writeString(root.resolve("live-verified.json"),JSON.writeValueAsString(Map.of("task",task,"batch",batch,"actorCobble",cobble,"pickDamage",pick.getDamageValue(),"viewerDiamonds",viewer.getInventory().getItem(8).getCount())));liveVerified=true;phase=1;phaseTick=server.getTickCount();return;
            }
            if(phase==1&&server.getTickCount()-phaseTick>40){reset(body,server);server.overworld().setBlockAndUpdate(new BlockPos(324,170,1),Blocks.OBSIDIAN.defaultBlockState());body.getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));newTask(tasks,actions,viewer.getUUID(),"取消延迟挖掘",calls("break_block","place_block"));phase=2;earlyStop=false;phaseTick=server.getTickCount();return;}
            if(phase==2){
                if(!earlyStop&&batch.state().equals("EXECUTING")&&server.getTickCount()-phaseTick>=10){body.gameMode.handleBlockBreakAction(new BlockPos(324,170,1),net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,net.minecraft.core.Direction.UP,body.level().getMaxY(),9000);if(!((dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess)body.gameMode).mineagent$hasDelayed())throw new IllegalStateException("DELAYED_MINING_NOT_ARMED");Files.writeString(root.resolve("delayed-before-cancel.json"),JSON.writeValueAsString(Map.of("delayed",true,"taskId",taskId)));earlyStop=true;cancelTaskId=taskId.toString();}
                if(task.status()!=TaskStatus.CANCELLED)return;
                var access=(dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess)body.gameMode;
                if(access.mineagent$hasDelayed()||access.mineagent$isDestroying()||body.miningTarget().isPresent()||!server.overworld().getBlockState(new BlockPos(324,170,1)).is(Blocks.OBSIDIAN)||!server.overworld().getBlockState(new BlockPos(328,170,2)).isAir())throw new IllegalStateException("CANCEL_DID_NOT_STOP_NATIVE");
                Files.writeString(root.resolve("cancel-verified.json"),JSON.writeValueAsString(Map.of("task",task,"batch",batch,"delayed",access.mineagent$hasDelayed())));cancelTaskId=null;reset(body,server);server.overworld().setBlockAndUpdate(new BlockPos(324,170,1),Blocks.STONE.defaultBlockState());newTask(tasks,actions,viewer.getUUID(),"原生事件拒绝",calls("break_block","place_block"));phase=3;return;
            }
            if(phase==3){
                if(!Set.of("FAILED","INTERRUPTED").contains(batch.state()))return;
                if(!batch.error().equals("NATIVE_BREAK_REJECTED")||!server.overworld().getBlockState(new BlockPos(324,170,1)).is(Blocks.STONE)||!server.overworld().getBlockState(new BlockPos(328,170,2)).isAir())throw new IllegalStateException("NATIVE_DENIAL_NOT_PRESERVED");
                Files.writeString(root.resolve("denied-verified.json"),JSON.writeValueAsString(Map.of("task",task,"batch",batch)));reset(body,server);server.overworld().setBlockAndUpdate(new BlockPos(324,170,1),Blocks.OBSIDIAN.defaultBlockState());newTask(tasks,actions,viewer.getUUID(),"挖掘目标变更",calls("break_block","place_block"));phase=4;phaseTick=server.getTickCount();return;
            }
            if(phase==4){
                if(batch.state().equals("EXECUTING")&&server.getTickCount()-phaseTick>=10)server.overworld().setBlockAndUpdate(new BlockPos(324,170,1),Blocks.GOLD_BLOCK.defaultBlockState());
                if(!Set.of("FAILED","INTERRUPTED").contains(batch.state()))return;
                if(!server.overworld().getBlockState(new BlockPos(324,170,1)).is(Blocks.GOLD_BLOCK)||!server.overworld().getBlockState(new BlockPos(328,170,2)).isAir())throw new IllegalStateException("REPLACEMENT_WAS_MINED");
                Files.writeString(root.resolve("changed-verified.json"),JSON.writeValueAsString(Map.of("task",task,"batch",batch)));reset(body,server);server.overworld().setBlockAndUpdate(new BlockPos(334,170,1),Blocks.BEDROCK.defaultBlockState());newTask(tasks,actions,viewer.getUUID(),"不可到达目标",calls("move_to","place_block"));phase=5;return;
            }
            if(phase==5){if(!Set.of("FAILED","INTERRUPTED").contains(batch.state()))return;if(!batch.error().equals("NAVIGATION_NOT_REACHED"))throw new IllegalStateException("UNREACHABLE_NOT_DIAGNOSED");Files.writeString(root.resolve("unreachable-verified.json"),JSON.writeValueAsString(Map.of("task",task,"batch",batch)));reset(body,server);bodies.setMode(agent,viewer.getUUID(),true,dev.mineagent.runtime.api.agent.AgentMode.CREATOR);body.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_PICKAXE));newTask(tasks,actions,viewer.getUUID(),"创造手持物保留",List.of(Map.of("id","place","name","place_block","arguments","{\"x\":324,\"y\":170,\"z\":2,\"block\":\"minecraft:cobblestone\"}")));phase=6;return;}
            if(phase==6){if(!batch.state().equals("COMPLETED"))return;if(!body.getMainHandItem().is(Items.DIAMOND_PICKAXE))throw new IllegalStateException("CREATIVE_HELD_ITEM_LOST");Files.writeString(root.resolve("creative-verified.json"),JSON.writeValueAsString(Map.of("batch",batch,"heldItem","minecraft:diamond_pickaxe")));reset(body,server);bodies.setMode(agent,viewer.getUUID(),true,dev.mineagent.runtime.api.agent.AgentMode.SURVIVAL);body.getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));server.overworld().setBlockAndUpdate(new BlockPos(324,170,1),Blocks.OBSIDIAN.defaultBlockState());newTask(tasks,actions,viewer.getUUID(),"重启中断原生动作",calls("break_block",null));phase=7;phaseTick=server.getTickCount();return;}
            if(phase==7&&batch.state().equals("EXECUTING")&&server.getTickCount()-phaseTick>=10){Files.writeString(root.resolve("restart-checkpoint.json"),JSON.writeValueAsString(Map.of("taskId",taskId,"batch",batch,"block","minecraft:obsidian")));viewer.getInventory().setItem(8,viewerSlot);finished=true;}
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("phase",phase,"error",failure)));if(agent!=null){bodies.body(agent).ifPresent(b->{b.abortMining();b.movementController().stop();});bodies.remove(agent,viewer.getUUID(),true);}if(viewerSlot!=null)viewer.getInventory().setItem(8,viewerSlot);}
    }
    private static int count(MineAgentPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static void replayNext(WorldActionService actions,ManagedTask task)throws Exception{var raw=new ArrayList<Map<String,Object>>();for(var a:replay[replayCursor++].actions())raw.add(Map.of("id",a.callId(),"name",a.tool(),"arguments",JSON.writeValueAsString(a.tool().equals("place_block")?Map.of("x",a.x(),"y",a.y(),"z",a.z(),"block",a.block()):Map.of("x",a.x(),"y",a.y(),"z",a.z()))));actions.submit(task,raw);}
    private static void reset(MineAgentPlayer p,net.minecraft.server.MinecraftServer s){p.abortMining();p.movementController().stop();p.teleportTo(s.overworld(),322.5,170,1.5,Set.of(),-90,0,true);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));p.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,2));p.getInventory().setSelectedSlot(0);s.overworld().setBlockAndUpdate(new BlockPos(328,170,2),Blocks.AIR.defaultBlockState());}
    private static void newTask(TaskManager tasks,WorldActionService actions,UUID owner,String title,List<Map<String,Object>> calls)throws Exception{var task=tasks.create(agent,owner,title,50,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));taskId=task.taskId();task=tasks.completeStep(task.taskId(),task.revision(),"fixture_plan").task();actions.submit(task,calls);}
}
