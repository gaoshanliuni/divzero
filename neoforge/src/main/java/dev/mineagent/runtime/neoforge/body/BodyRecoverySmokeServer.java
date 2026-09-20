package dev.mineagent.runtime.neoforge.body;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.WorldActionJournal;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Explicit isolated save/restart boundaries. No Provider, raw replay, OS input, or fabricated health outcome. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class BodyRecoverySmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile UUID agent;
    public static volatile String wanted="",failure="";
    public static volatile boolean done;
    public static volatile String wantedDimension="";
    public static volatile float wantedHealth;
    public static final Set<String> seen=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<UUID,Map<String,Object>> logins=new HashMap<>();
    private static final Map<UUID,MineAgentPlayer> loadedBodies=new HashMap<>();
    private static final Map<String,UUID> tasks=new LinkedHashMap<>();
    private static UUID owner,holdToken;
    private static int phase,at,started=-1;
    private static MineAgentPlayer previous;
    private static boolean initialized;
    public static String stage(){return System.getProperty("mineagent.bodyRecoveryStage","");}
    private static boolean enabled(){return Boolean.getBoolean("mineagent.bodyRecoverySmoke");}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static Path root(MinecraftServer s){return s.getServerDirectory().resolve("body-recovery-evidence");}
    private static void save(MinecraftServer s,String file,Object value)throws Exception{var path=root(s).resolve(stage()).resolve(file);Files.createDirectories(path.getParent());Files.writeString(path,JSON.writeValueAsString(value));}
    private static int count(MineAgentPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static int deaths(MineAgentPlayer p){return p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));}
    private static int diamonds(ServerLevel l){return l.getEntitiesOfClass(ItemEntity.class,new AABB(860,-64,-25,935,185,25)).stream().filter(e->e.getItem().is(Items.DIAMOND)).mapToInt(e->e.getItem().getCount()).sum();}
    private static int dropped(ServerLevel l,Item item){return l.getEntitiesOfClass(ItemEntity.class,new AABB(860,-64,-25,935,185,25)).stream().filter(e->e.getItem().is(item)).mapToInt(e->e.getItem().getCount()).sum();}
    private static Map<String,Object> state(MineAgentPlayer p){var n=new LinkedHashMap<>(BodySurvivalSmokeServer.snapshot(p));n.put("dimension",p.level().dimension().identifier().toString());n.put("loadedNativeState",p.loadedNativeState());n.put("endReturnAccepted",p.endReturnAccepted());n.put("wonGame",p.wonGame);n.put("seenCredits",p.seenCredits);n.put("owner",p.ownerPlayerId());n.put("canAct",p.canAct());n.put("emeralds",count(p,Items.EMERALD));return n;}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(enabled()&&event.getEntity() instanceof MineAgentPlayer p){logins.put(p.agentId(),state(p));loadedBodies.put(p.agentId(),p);}}
    @SubscribeEvent public static void starting(net.neoforged.neoforge.event.server.ServerStartedEvent event)throws Exception{
        if(!enabled()||!stage().equals("resume-death"))return;
        var s=event.getServer();var n=JSON.readTree(Files.readString(root(s).resolve("journal.json")));var id=UUID.fromString(n.path("agent").asText());var ownerId=UUID.fromString(n.path("owner").asText());
        var bodies=MineAgentRuntimeServices.bodies(s);require(bodies.body(id).isEmpty(),"RESTORE_NOT_ASYNCHRONOUS");
        require(bodies.rename(id,ownerId,false,"Recovery latest name")&&bodies.setMode(id,ownerId,false,AgentMode.CREATOR),"PENDING_RESTORE_MUTATION_REJECTED");
        save(s,"loading-mutation.json",Map.of("agent",id,"ready",false,"requestedMode","CREATOR","name","Recovery latest name"));
    }
    private static void floor(ServerLevel l){for(int x=878;x<=912;x++)for(int z=-2;z<=8;z++){l.setBlockAndUpdate(new BlockPos(x,169,z),Blocks.STONE.defaultBlockState());for(int y=170;y<=174;y++)l.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}}
    private static void observe(ServerPlayer viewer,MineAgentPlayer p,String label){viewer.setGameMode(GameType.SPECTATOR);viewer.teleportTo(p.level(),p.getX(),p.getY()+3,p.getZ()+5,Set.of(),180,36,true);wantedDimension=p.level().dimension().identifier().toString();wantedHealth=p.getHealth();wanted=label;}
    private static void hold(MineAgentPlayer p){p.movementController().stop();holdToken=UUID.randomUUID();require(p.claimTaskControl(holdToken,()->true,()->{}),"RECOVERY_HOLD_BUSY");}
    private static void release(MineAgentPlayer p){if(holdToken!=null)p.releaseTaskControl(holdToken);holdToken=null;}
    private static void respawnPoint(MineAgentPlayer p,MinecraftServer s){p.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(Level.OVERWORLD,new BlockPos(883,170,5),0,0),true),false);}
    private static WorldActionJournal.Batch batch(MinecraftServer s,String key){return MineAgentRuntimeServices.taskExecutor(s).worldActions().list(owner).stream().filter(b->b.taskId().equals(tasks.get(key))).findFirst().orElseThrow();}
    private static Object taskEvidence(MinecraftServer s,String key){return Map.of("task",MineAgentRuntimeServices.tasks(s).get(tasks.get(key)).orElseThrow(),"batch",batch(s,key));}
    private static void startTask(MinecraftServer s,MineAgentPlayer p,String key,boolean queued,int x)throws Exception{
        var manager=MineAgentRuntimeServices.tasks(s);var task=manager.create(agent,owner,key,50,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));
        task=manager.completeStep(task.taskId(),task.revision(),"fixture_plan").task();tasks.put(key,task.taskId());
        var calls=new ArrayList<Map<String,Object>>();
        if(!queued){p.level().setBlockAndUpdate(new BlockPos(x,170,1),Blocks.OBSIDIAN.defaultBlockState());calls.add(Map.of("id","mine","name","break_block","arguments",JSON.writeValueAsString(Map.of("x",x,"y",170,"z",1))));}
        calls.add(Map.of("id","place","name","place_block","arguments",JSON.writeValueAsString(Map.of("x",x+3,"y",170,"z",1,"block","minecraft:cobblestone"))));
        MineAgentRuntimeServices.taskExecutor(s).worldActions().submit(task,calls);
    }
    private static void miningSetup(MineAgentPlayer p,int x){p.teleportTo(p.level(),x-1.5,170,1.5,Set.of(),0,0,true);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));p.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,2));p.getInventory().setSelectedSlot(0);}
    private static void kill(MineAgentPlayer p){p.invulnerableTime=0;require(p.hurtServer(p.level(),p.damageSources().genericKill(),1000)&&p.deathAccepted()&&!p.taskControlOwned()&&p.miningTarget().isEmpty(),"RECOVERY_DEATH_NOT_ACCEPTED");}
    private static void stoppedTasks(MinecraftServer s,String error,String... keys)throws Exception{for(String key:keys){var t=MineAgentRuntimeServices.tasks(s).get(tasks.get(key)).orElseThrow();var b=batch(s,key);require(t.status()==TaskStatus.PAUSED&&b.state().equals("INTERRUPTED")&&b.error().equals(error),"RECOVERY_TASK_REPLAYED_"+key);require(MineAgentRuntimeServices.taskExecutor(s).worldActions().planningAttempts(t)==0,"RECOVERY_MODEL_CALLED");save(s,key+"-verified.json",taskEvidence(s,key));}}
    private static void journal(MinecraftServer s)throws Exception{Files.createDirectories(root(s));Files.writeString(root(s).resolve("journal.json"),JSON.writeValueAsString(Map.of("agent",agent,"owner",owner,"tasks",tasks,"world",MineAgentRuntimeServices.worldId(s))));}
    private static void stop(MinecraftServer s,String status)throws Exception{journal(s);save(s,"result.json",Map.of("stage",stage(),"status",status,"agent",agent,"owner",owner,"serverTick",s.getTickCount(),"modelCalls",0,"systemInputInjected",false,"fullV1",false));done=true;s.halt(false);}
    private static void hardcore(MinecraftServer s)throws Exception{
        // Test-only world settings setup, persisted by the ordinary Native world save.
        var data=s.getWorldData();var field=data.getClass().getDeclaredField("settings");field.setAccessible(true);var old=(LevelSettings)field.get(data);
        field.set(data,new LevelSettings(old.levelName(),old.gameType(),new LevelSettings.DifficultySettings(Difficulty.HARD,true,true),old.allowCommands(),old.dataConfiguration(),old.lifecycle()));
        require(s.isHardcore(),"HARDCORE_SETUP_FAILED");
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||!failure.isEmpty())return;var s=event.getServer();if(started<0)started=s.getTickCount();
        try{
            require(s.getTickCount()-started<1600,"BODY_RECOVERY_TIMEOUT_"+stage()+"_"+phase);
            var bodies=MineAgentRuntimeServices.bodies(s);
            if(!initialized){initialized=true;if(!stage().equals("prepare-death")){var n=JSON.readTree(Files.readString(root(s).resolve("journal.json")));agent=UUID.fromString(n.path("agent").asText());owner=UUID.fromString(n.path("owner").asText());n.path("tasks").properties().forEach(e->tasks.put(e.getKey(),UUID.fromString(e.getValue().asText())));require(n.path("world").asText().equals(MineAgentRuntimeServices.worldId(s).toString()),"RECOVERY_WORLD_CHANGED");}}
            var viewer=s.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);
            if(viewer==null)return;
            MineAgentPlayer p=agent==null?null:bodies.body(agent).orElse(null);
            if(agent!=null&&p==null&&!(stage().equals("verify-mode")&&phase>=2)){require(bodies.definitions().stream().anyMatch(d->d.agentId().equals(agent)),"RECOVERY_DEFINITION_MISSING");return;}
            switch(stage()){
                case "prepare-death"->{
                    if(phase==0){s.setDifficulty(Difficulty.HARD,true);s.overworld().getGameRules().set(GameRules.KEEP_INVENTORY,false,s);s.overworld().getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION,false,s);floor(s.overworld());owner=viewer.getUUID();agent=bodies.create("Recovery body",viewer,AgentMode.SURVIVAL).agentId();p=bodies.body(agent).orElseThrow();miningSetup(p,882);p.getInventory().setItem(2,new ItemStack(Items.DIAMOND,5));p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD,3));p.inventoryMenu.getSlot(1).set(new ItemStack(Items.GOLD_INGOT,2));respawnPoint(p,s);hold(p);observe(viewer,p,"prepared");save(s,"setup.json",Map.of("body",state(p),"setup","Explicit floor, items, task fixture_plan and no Provider"));phase=1;}
                    else if(phase==1&&seen.contains("prepared")){release(p);startTask(s,p,"death-active",false,882);startTask(s,p,"death-queued",true,889);phase=2;at=s.getTickCount();}
                    else if(phase==2&&s.getTickCount()-at>8&&batch(s,"death-active").state().equals("EXECUTING")&&p.miningTarget().isPresent()){
                        require(batch(s,"death-queued").state().equals("READY"),"DEATH_QUEUE_ALREADY_EXECUTED");previous=p;kill(p);require(!((AgentMiningAccess)p.gameMode).mineagent$hasDelayed()&&!((AgentMiningAccess)p.gameMode).mineagent$isDestroying(),"DEATH_MINING_CONTINUED");save(s,"stopped-dead.json",Map.of("body",state(p),"diamondsInWorld",diamonds(s.overworld()),"emeraldsInWorld",dropped(s.overworld(),Items.EMERALD),"goldInWorld",dropped(s.overworld(),Items.GOLD_INGOT),"active",taskEvidence(s,"death-active"),"queued",taskEvidence(s,"death-queued")));stop(s,"STOPPED_AFTER_CONFIRMED_DEATH_BEFORE_RESPAWN");
                    }
                }
                case "resume-death"->{
                    require(p!=null,"LOADED_BODY_MISSING");
                    if(phase==0&&p.isAlive()&&!p.deathAccepted()){
                        require(logins.containsKey(agent)&&((Number)logins.get(agent).get("health")).floatValue()==0&&p!=loadedBodies.get(agent)&&loadedBodies.get(agent).isRemoved(),"DEAD_NBT_NOT_RESPAWNED");
                        require(p.gameMode.getGameModeForPlayer()==GameType.CREATIVE&&p.getDisplayName().getString().equals("Recovery latest name"),"STALE_PENDING_RESTORE_DEFINITION");
                        save(s,"loaded-candidate.json",Map.of("login",logins.get(agent),"body",state(p),"old",state(loadedBodies.get(agent)),"items",s.overworld().getEntitiesOfClass(ItemEntity.class,new AABB(860,-64,-25,935,185,25)).stream().map(e->Map.of("item",e.getItem().toString(),"position",e.position().toString(),"id",e.getUUID())).toList(),"diamonds",diamonds(s.overworld()),"emeralds",dropped(s.overworld(),Items.EMERALD),"gold",dropped(s.overworld(),Items.GOLD_INGOT)));
                        require(bodies.setMode(agent,owner,false,AgentMode.SURVIVAL),"RECOVERY_RETURN_TO_SURVIVAL");
                        require(deaths(p)==1&&p.getHealth()==20&&p.getFoodData().getFoodLevel()==20&&count(p,Items.DIAMOND)==0&&diamonds(s.overworld())==5&&dropped(s.overworld(),Items.EMERALD)==3&&dropped(s.overworld(),Items.GOLD_INGOT)==2,"DEAD_NBT_LOOT_REPLAYED");
                        if(MineAgentRuntimeServices.tasks(s).get(tasks.get("death-active")).orElseThrow().status()!=TaskStatus.PAUSED)return;
                        stoppedTasks(s,"SERVER_RESTARTED_NO_REPLAY","death-active","death-queued");require(s.overworld().getBlockState(new BlockPos(882,170,1)).is(Blocks.OBSIDIAN)&&s.overworld().getBlockState(new BlockPos(885,170,1)).isAir()&&s.overworld().getBlockState(new BlockPos(892,170,1)).isAir(),"DEAD_NBT_WORLD_CHANGED");
                        save(s,"loaded-dead-verified.json",Map.of("login",logins.get(agent),"fresh",state(p),"diamondsInWorld",diamonds(s.overworld()),"emeraldsInWorld",dropped(s.overworld(),Items.EMERALD),"goldInWorld",dropped(s.overworld(),Items.GOLD_INGOT)));hold(p);observe(viewer,p,"restored-death");phase=1;
                    }else if(phase==1&&seen.contains("restored-death")){release(p);miningSetup(p,900);startTask(s,p,"same-jvm-active",false,900);startTask(s,p,"same-jvm-queued",true,904);at=s.getTickCount();phase=2;}
                    else if(phase==2&&s.getTickCount()-at>8&&p.miningTarget().isPresent()){previous=p;kill(p);save(s,"same-jvm-death.json",state(p));phase=3;}
                    else if(phase==3&&p!=previous&&p.isAlive()){
                        if(MineAgentRuntimeServices.tasks(s).get(tasks.get("same-jvm-active")).orElseThrow().status()!=TaskStatus.PAUSED)return;
                        stoppedTasks(s,"BODY_CONTROL_CHANGED","same-jvm-active","same-jvm-queued");require(s.overworld().getBlockState(new BlockPos(900,170,1)).is(Blocks.OBSIDIAN)&&s.overworld().getBlockState(new BlockPos(903,170,1)).isAir()&&s.overworld().getBlockState(new BlockPos(907,170,1)).isAir(),"SAME_JVM_TASK_REPLAYED");
                        require(deaths(p)==2&&previous.isRemoved(),"SAME_JVM_REPLACEMENT");save(s,"same-jvm-respawn.json",Map.of("old",state(previous),"fresh",state(p)));hold(p);observe(viewer,p,"same-jvm-respawn");phase=4;
                    }else if(phase==4&&seen.contains("same-jvm-respawn")){
                        release(p);var end=s.getLevel(Level.END);require(end!=null,"END_MISSING");floor(end);require(bodies.changeDimension(agent,end,new Vec3(899,170,1.5)),"END_TRAVEL_FAILED");miningSetup(p,900);p.getInventory().setItem(2,new ItemStack(Items.DIAMOND,7));p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD,3));p.inventoryMenu.getSlot(1).set(new ItemStack(Items.GOLD_INGOT,2));p.giveExperiencePoints(100);p.setHealth(9);p.getFoodData().setFoodLevel(8);p.getFoodData().setSaturation(0);respawnPoint(p,s);hold(p);observe(viewer,p,"before-credits");phase=5;
                    }else if(phase==5&&seen.contains("before-credits")){release(p);startTask(s,p,"end-active",false,900);startTask(s,p,"end-queued",true,904);at=s.getTickCount();phase=6;}
                    else if(phase==6&&s.getTickCount()-at>8&&p.miningTarget().isPresent()){
                        p.showEndCredits();p.showEndCredits();require(p.isRemoved()&&p.wonGame&&p.endReturnAccepted()&&!p.deathAccepted()&&deaths(p)==2&&!p.taskControlOwned(),"END_RETURN_NOT_PENDING");save(s,"stopped-end.json",Map.of("body",state(p),"active",taskEvidence(s,"end-active"),"queued",taskEvidence(s,"end-queued")));stop(s,"STOPPED_DURING_END_CREDITS_PENDING_RETURN");
                    }
                }
                case "resume-end"->{
                    require(p!=null,"END_LOADED_BODY_MISSING");
                    if(phase==0&&p.level()==s.overworld()&&!p.endReturnAccepted()){
                        var login=logins.get(agent);require(login!=null&&login.get("dimension").equals("minecraft:the_end")&&p!=loadedBodies.get(agent)&&loadedBodies.get(agent).isRemoved(),"END_NBT_NOT_RETURNED");
                        require(deaths(p)==2&&p.getHealth()==9&&p.getFoodData().getFoodLevel()==8&&count(p,Items.DIAMOND)==7&&count(p,Items.EMERALD)==3&&count(p,Items.GOLD_INGOT)==2&&p.totalExperience==100&&p.seenCredits&&!p.wonGame,"END_KEEP_ALL_LOST");
                        if(MineAgentRuntimeServices.tasks(s).get(tasks.get("end-active")).orElseThrow().status()!=TaskStatus.PAUSED)return;
                        stoppedTasks(s,"SERVER_RESTARTED_NO_REPLAY","end-active","end-queued");save(s,"end-return-verified.json",Map.of("login",login,"fresh",state(p)));hold(p);observe(viewer,p,"restored-end");phase=1;
                    }else if(phase==1&&seen.contains("restored-end")){
                        release(p);require(bodies.changeDimension(agent,s.getLevel(Level.END),new Vec3(899,170,1.5)),"LIVE_END_TRAVEL");previous=p;p.showEndCredits();p.showEndCredits();save(s,"live-end-pending.json",state(p));phase=2;
                    }else if(phase==2&&p!=previous&&p.level()==s.overworld()){
                        require(previous.isRemoved()&&p.getHealth()==9&&count(p,Items.DIAMOND)==7&&p.totalExperience==100&&deaths(p)==2&&!p.endReturnAccepted(),"LIVE_END_RETURN_FAILED");save(s,"live-end-return.json",Map.of("old",state(previous),"fresh",state(p)));hold(p);observe(viewer,p,"live-end-return");phase=3;
                    }else if(phase==3&&seen.contains("live-end-return")){release(p);hardcore(s);previous=p;kill(p);save(s,"hardcore-death.json",state(p));phase=4;}
                    else if(phase==4&&p!=previous&&p.isAlive()){
                        require(p.isSpectator()&&!p.canAct()&&deaths(p)==3&&s.isHardcore(),"NATIVE_HARDCORE_RESPAWN");save(s,"hardcore-respawn.json",state(p));observe(viewer,p,"hardcore-spectator");phase=5;
                    }else if(phase==5&&seen.contains("hardcore-spectator"))stop(s,"HARDCORE_SPECTATOR_SAVED");
                }
                case "resume-hardcore"->{
                    require(p!=null,"HARDCORE_BODY_MISSING");
                    if(phase==0){require(s.isHardcore()&&p.loadedNativeState()&&p==loadedBodies.get(agent)&&p.isSpectator()&&!p.canAct()&&deaths(p)==3,"HARDCORE_MODE_RESET_ON_RESTART");save(s,"hardcore-loaded.json",Map.of("login",logins.get(agent),"body",state(p),"requestedMode",bodies.definitions().getFirst().mode()));observe(viewer,p,"hardcore-restored");at=s.getTickCount();phase=1;}
                    else if(phase==1&&seen.contains("hardcore-restored")&&s.getTickCount()-at>=40){
                        require(!p.claimTaskControl(UUID.randomUUID(),()->true,()->{})&&!p.beginMining(new BlockPos(882,170,1)),"SPECTATOR_ACTION_ACCEPTED");
                        var tm=MineAgentRuntimeServices.tasks(s);var denied=tm.create(agent,owner,"spectator request",50,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));denied=tm.completeStep(denied.taskId(),denied.revision(),"fixture_plan").task();
                        try{MineAgentRuntimeServices.taskExecutor(s).worldActions().submit(denied,List.of(Map.of("id","blocked","name","place_block","arguments","{\"x\":895,\"y\":170,\"z\":7,\"block\":\"minecraft:cobblestone\"}")));throw new AssertionError("SPECTATOR_REQUEST_STORED");}catch(IllegalStateException expected){require(expected.getMessage().equals("BODY_UNAVAILABLE"),"SPECTATOR_WRONG_ERROR");}
                        require(!MineAgentRuntimeServices.taskExecutor(s).worldActions().hasPlan(denied.taskId(),denied.intentRevision()),"SPECTATOR_PLAN_STORED");save(s,"spectator-request-rejected.json",Map.of("task",denied.taskId(),"code","BODY_UNAVAILABLE","planStored",false));tm.transition(denied.taskId(),denied.revision(),true,TaskStatus.CANCELLED);
                        require(!bodies.setMode(agent,UUID.randomUUID(),false,AgentMode.SURVIVAL)&&p.isSpectator(),"PEER_MODE_CHANGE_ACCEPTED");
                        var defaultMode=s.getWorldData().getGameType();s.getWorldData().setGameType(GameType.SPECTATOR);var created=bodies.createAt("new creator",owner,AgentMode.CREATOR,s.overworld(),new Vec3(890,170,5)).agentId();require(bodies.body(created).orElseThrow().gameMode.getGameModeForPlayer()==GameType.CREATIVE,"NEW_BODY_WRONGLY_PRESERVED_SPECTATOR");bodies.remove(created,owner,true);s.getWorldData().setGameType(defaultMode);
                        require(bodies.setMode(agent,owner,false,AgentMode.SURVIVAL)&&p.canAct(),"EXPLICIT_MODE_CHANGE_FAILED");save(s,"explicit-mode.json",state(p));observe(viewer,p,"explicit-survival");phase=2;
                    }else if(phase==2&&seen.contains("explicit-survival"))stop(s,"EXPLICIT_MODE_OVERRIDE_SAVED");
                }
                case "verify-mode"->{
                    if(phase==0){require(p!=null&&s.isHardcore()&&p.loadedNativeState()&&p.canAct()&&p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL&&deaths(p)==3,"EXPLICIT_MODE_NOT_PERSISTED");save(s,"mode-restored.json",state(p));hold(p);observe(viewer,p,"final-visible");phase=1;}
                    else if(phase==1&&seen.contains("final-visible")){require(bodies.remove(agent,owner,false),"RECOVERY_REMOVE_FAILED");wanted="removed";phase=2;}
                    else if(phase==2&&seen.contains("removed")){require(bodies.body(agent).isEmpty()&&bodies.definitions().stream().noneMatch(d->d.agentId().equals(agent))&&s.getPlayerList().getPlayer(agent)==null&&s.overworld().getEntity(agent)==null,"REMOVED_BODY_STILL_BOUND");stop(s,"BODY_RECOVERY_MATRIX_VERIFIED");}
                }
                default->throw new IllegalArgumentException("BODY_RECOVERY_STAGE");
            }
        }catch(Exception e){failure=e.toString();save(s,"failure.json",Map.of("phase",phase,"error",failure,"agent",agent==null?"":agent.toString(),"body",agent==null?Map.of():MineAgentRuntimeServices.bodies(s).body(agent).map(BodyRecoverySmokeServer::state).orElse(Map.of())));s.halt(false);}
    }
}
