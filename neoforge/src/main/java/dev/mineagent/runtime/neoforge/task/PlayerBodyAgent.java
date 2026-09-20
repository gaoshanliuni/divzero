package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.task.PlayerControlPlan;

import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.network.PlayerBodyPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server authorizes a bounded input sequence for its requesting real player, never for an AIPlayer or another user. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class PlayerBodyAgent {
    private static final UUID NONE=new UUID(0,0);
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<MinecraftServer,Map<UUID,Job>> JOBS=new IdentityHashMap<>();
    private static final Set<String> LIVE=Set.of("PREPARING","PLANNING","REVIEW","ARMING","RUNNING");
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"mineagent-player-body-journal");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class Job {
        final UUID operation=UUID.randomUUID(),world,agent;final ServerPlayer player;final Path database;final String prompt,dimension,name;
        final AtomicBoolean permit=new AtomicBoolean(true);final long permissionGeneration;
        final net.minecraft.world.phys.Vec3 origin;final float yaw,pitch;
        String state="PREPARING",error="",encoded="",hash="";UUID consent=NONE;PlayerControlPlan plan;
        long expires=System.currentTimeMillis()+200000,heartbeat,sequence;int completed;long journalRevision;volatile boolean journalFailed;
        CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);
        Job(ServerPlayer p,UUID agent,String name,String prompt){player=p;this.agent=agent;this.name=name;this.prompt=prompt;var s=p.level().getServer();world=MineAgentRuntimeServices.worldId(s);dimension=p.level().dimension().identifier().toString();database=s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");origin=p.position();yaw=p.getYRot();pitch=p.getXRot();permissionGeneration=MineAgentRuntimeServices.config(s).permissionGeneration(p.getUUID(),PermissionAction.START_TASK);}
    }
    private PlayerBodyAgent(){}
    private static Map<UUID,Job> jobs(MinecraftServer s){return JOBS.computeIfAbsent(s,k->new HashMap<>());}
    private static boolean current(Job j){try{var s=j.player.level().getServer();return j.permit.get()&&!j.journalFailed&&s.isSameThread()&&s.getPlayerList().getPlayer(j.player.getUUID())==j.player&&j.player.isAlive()&&!j.player.isSpectator()&&!j.player.isPassenger()&&j.world.equals(MineAgentRuntimeServices.worldId(s))&&j.dimension.equals(j.player.level().dimension().identifier().toString())&&System.currentTimeMillis()<j.expires&&j.permissionGeneration==MineAgentRuntimeServices.config(s).permissionGeneration(j.player.getUUID(),PermissionAction.START_TASK)&&ServerTaskStart.allowed(j.player,j.agent)&&!"EXECUTING".equals(PlayerCommandAgent.observe(j.player).get("state"));}catch(RuntimeException invalid){return false;}}
    private static void say(ServerPlayer p,String message){p.sendSystemMessage(Component.literal("[AI 接管] "+message));}
    private static void signal(Job j,String action,String detail){if(j.player.level().getServer().getPlayerList().getPlayer(j.player.getUUID())==j.player)PacketDistributor.sendToPlayer(j.player,new PlayerBodyPayloads.Signal(j.operation,j.consent,action,++j.sequence,detail));}
    private static CompletableFuture<Void> save(Job j){
        try{var value=new LinkedHashMap<String,Object>();value.put("operation",j.operation);value.put("owner",j.player.getUUID());value.put("world",j.world);value.put("agent",j.agent);value.put("dimension",j.dimension);value.put("prompt",j.prompt);value.put("state",j.state);value.put("reason",j.error);value.put("plan",j.plan);value.put("planHash",j.hash);value.put("completedInputSteps",j.completed);value.put("origin",List.of(j.origin.x,j.origin.y,j.origin.z));value.put("observedPosition",List.of(j.player.getX(),j.player.getY(),j.player.getZ()));value.put("outcomeMeaning","INPUT_SEQUENCE_ONLY_NOT_GOAL_VERIFICATION");String encoded=JSON.writeValueAsString(value);
            j.saved=CompletableFuture.runAsync(()->{try{j.journalRevision=dev.mineagent.runtime.core.task.PlayerControlJournal.append(j.database,j.world,j.operation,j.journalRevision,encoded,System.currentTimeMillis());}catch(Exception failure){j.journalFailed=true;throw new CompletionException(failure);}},IO);
            j.saved.exceptionally(e->{MineAgentRuntimeMod.LOGGER.warn("Player body journal failed operation={}",j.operation);return null;});return j.saved;
        }catch(Exception e){j.journalFailed=true;return CompletableFuture.failedFuture(e);}
    }
    public static Map<String,Object> observe(ServerPlayer p){var j=jobs(p.level().getServer()).get(p.getUUID());return j==null?Map.of("state","NONE"):Map.of("operation",j.operation,"state",j.state,"reason",j.error,"completedInputSteps",j.completed,"journalSaved",j.saved.isDone()&&!j.saved.isCompletedExceptionally());}
    public static void submit(ServerPlayer p,UUID agent,String prompt)throws Exception{
        var s=p.level().getServer();if(!s.isSameThread()||!ServerTaskStart.allowed(p,agent)||p.isSpectator()||p.isPassenger())throw new SecurityException("PLAYER_BODY_PERMISSION");
        if(prompt==null||prompt.isBlank()||prompt.length()>4096)throw new IllegalArgumentException("PLAYER_BODY_PROMPT");
        var prior=jobs(s).get(p.getUUID());if(prior!=null&&LIVE.contains(prior.state))throw new IllegalStateException("PLAYER_BODY_BUSY");
        String name=MineAgentRuntimeServices.bodies(s).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElseThrow().displayName();var j=new Job(p,agent,name,prompt);jobs(s).put(p.getUUID(),j);
        String context=context(p,prompt);say(p,"正在生成本人身体操作计划（调用当前模型）。不会自动启动；可用 /ai body stop 取消。请暂时保持位置和朝向。");
        save(j).whenComplete((v,failure)->s.execute(()->{
            if(!current(j)||!j.state.equals("PREPARING")){if(LIVE.contains(j.state))finish(j,"STOPPED","CONTEXT_CHANGED");return;}
            if(failure!=null){finish(j,"FAILED","JOURNAL_FAILED");return;}j.state="PLANNING";
            MineAgentRuntimeServices.worker(s).planPresentation(MineAgentRuntimeServices.config(s),j.operation,context,j.permit::get).whenComplete((response,error)->s.execute(()->{
                if(!current(j)||!j.state.equals("PLANNING")){if(LIVE.contains(j.state))finish(j,"STOPPED","CONTEXT_CHANGED");return;}
                try{if(error!=null||response==null||!response.type().equals("model.result"))throw new IllegalStateException("PLAYER_BODY_MODEL_FAILED");j.plan=PlayerControlPlan.parse(String.valueOf(response.payload().get("text")));j.encoded=JSON.writeValueAsString(j.plan);j.hash=RuntimePackageCanonicalizer.sha256(j.encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));j.state="REVIEW";j.expires=System.currentTimeMillis()+120000;
                    save(j).whenComplete((ignored,storedError)->s.execute(()->{if(!current(j)||!j.state.equals("REVIEW"))return;if(storedError!=null){finish(j,"FAILED","JOURNAL_FAILED");return;}say(p,"计划已就绪："+j.plan.steps().size()+" 步，约 "+j.plan.steps().stream().mapToInt(PlayerControlPlan.Step::ticks).sum()/20.0+" 秒。先查看全部动作，再在本机明确启动。");p.sendSystemMessage(Component.literal("[查看并启动接管]").withStyle(st->st.withColor(0xBBD4A4).withClickEvent(new ClickEvent.RunCommand("/ai body review "+j.operation))).append(Component.literal("  [取消接管]").withStyle(st->st.withClickEvent(new ClickEvent.RunCommand("/ai body stop"))))); }));
                }catch(Exception invalid){finish(j,"FAILED","PLAYER_BODY_MODEL_FAILED");}
            }));
        }));
    }
    private static String context(ServerPlayer p,String prompt)throws Exception{
        var hotbar=new ArrayList<String>();for(int slot=0;slot<9;slot++)hotbar.add(slot+":"+p.getInventory().getItem(slot));
        var nearby=new ArrayList<String>();for(var pos:net.minecraft.core.BlockPos.betweenClosed(p.blockPosition().offset(-2,-1,-2),p.blockPosition().offset(2,2,2))){if(!p.level().hasChunkAt(pos))continue;var block=p.level().getBlockState(pos);if(!block.isAir())nearby.add(pos.toShortString()+":"+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()));if(nearby.size()>=60)break;}
        return PlayerControlPlan.instructions()+"\n当前真实玩家状态（不是独立AI身体）："+JSON.writeValueAsString(Map.of("dimension",p.level().dimension().identifier().toString(),"position",List.of(p.getX(),p.getY(),p.getZ()),"yaw",p.getYRot(),"pitch",p.getXRot(),"health",p.getHealth(),"hotbar",hotbar,"nearbyBlocks",nearby))+"\n仅生成一次短操作序列；不能保证寻路或任务完成。LOOK按ticks逐步相对旋转。ATTACK/USE为真实左/右键，可能挖掘、攻击、放置或使用当前物品。\n请求："+prompt;
    }
    public static int review(ServerPlayer p,UUID operation){var j=jobs(p.level().getServer()).get(p.getUUID());if(j==null||!j.operation.equals(operation)||!j.state.equals("REVIEW")||!current(j)){say(p,"没有有效待审计划；旧计划不会重放。");return 0;}PacketDistributor.sendToPlayer(p,new PlayerBodyPayloads.Offer(j.operation,j.world,p.getUUID(),j.dimension,j.name,j.encoded));return 1;}
    public static int stop(ServerPlayer p){var j=jobs(p.level().getServer()).get(p.getUUID());if(j!=null&&LIVE.contains(j.state))finish(j,"STOPPED","USER_STOP");else say(p,"当前没有接管操作。");return 1;}
    public static int status(ServerPlayer p){say(p,observe(p).toString());return 1;}
    public static void decide(ServerPlayer p,PlayerBodyPayloads.Decision d){
        var j=jobs(p.level().getServer()).get(p.getUUID());if(j==null||j.player!=p||!j.operation.equals(d.operation()))return;
        if(d.action().equals("STOP")){if(LIVE.contains(j.state)&&(j.consent.equals(NONE)||j.consent.equals(d.consent())))finish(j,"STOPPED",d.reason().isEmpty()?"USER_STOP":d.reason());return;}
        if(d.action().equals("START")){
            if(j.state.equals("RUNNING")&&j.consent.equals(d.consent()))return;
            if(!j.state.equals("REVIEW")||!current(j)||d.consent().equals(NONE))return;
            j.consent=d.consent();
            if(p.position().distanceToSqr(j.origin)>4||Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot()-j.yaw))>15||Math.abs(p.getXRot()-j.pitch)>15){finish(j,"STOPPED","PLAYER_CONTEXT_MOVED");return;}
            j.state="ARMING";save(j).whenComplete((v,error)->p.level().getServer().execute(()->{
                if(!current(j)||!j.state.equals("ARMING")){if(LIVE.contains(j.state))finish(j,"STOPPED","CONTEXT_CHANGED");return;}
                if(error!=null){finish(j,"FAILED","JOURNAL_FAILED");return;}j.state="RUNNING";j.expires=System.currentTimeMillis()+45000;j.heartbeat=System.currentTimeMillis();signal(j,"START",j.hash);save(j);
            }));return;
        }
        if(!j.state.equals("RUNNING")||!j.consent.equals(d.consent())||!current(j))return;
        if(d.completedSteps()<j.completed||d.completedSteps()>j.plan.steps().size()){finish(j,"STOPPED","INVALID_PROGRESS");return;}
        j.completed=d.completedSteps();j.heartbeat=System.currentTimeMillis();
        if(d.action().equals("FINISH")){finish(j,j.completed==j.plan.steps().size()?"COMPLETED":"STOPPED",j.completed==j.plan.steps().size()?"INPUT_SEQUENCE_FINISHED":"INCOMPLETE_SEQUENCE");}
    }
    private static void finish(Job j,String state,String reason){if(!LIVE.contains(j.state))return;j.permit.set(false);j.state=state;j.error=reason;signal(j,"STOP",reason);save(j);say(j.player,state.equals("COMPLETED")?"输入计划已结束，已释放控制。实际世界目标是否达成请核对；不会自动补跑。":"已停止接管："+reason+"；不重放已开始的动作。");}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var values=JOBS.get(e.getServer());if(values==null)return;for(var j:List.copyOf(values.values())){if(!LIVE.contains(j.state))continue;if(!current(j)){finish(j,"STOPPED","CONTEXT_CHANGED");continue;}if(j.state.equals("RUNNING")){if(System.currentTimeMillis()-j.heartbeat>2500){finish(j,"STOPPED","CLIENT_HEARTBEAT_LOST");continue;}if(e.getServer().getTickCount()%5==0)signal(j,"LEASE","");}}}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p){var values=JOBS.get(p.level().getServer());var j=values==null?null:values.remove(p.getUUID());if(j!=null)finish(j,"STOPPED","DISCONNECTED");}}
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e){var values=JOBS.remove(e.getServer());if(values!=null)values.values().forEach(j->finish(j,"STOPPED","SERVER_STOPPED"));}
}
