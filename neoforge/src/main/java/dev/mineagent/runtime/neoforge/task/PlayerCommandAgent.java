package dev.mineagent.runtime.neoforge.task;

import dev.mineagent.runtime.core.task.GameCommandPlan;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.*;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit model proposal -> player review -> real Minecraft command dispatcher. Never invokes an OS shell. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class PlayerCommandAgent {
    private static final Map<MinecraftServer,Map<UUID,Job>> JOBS=new WeakHashMap<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final class Job {final UUID operation=UUID.randomUUID(),world,agent;final ServerPlayer viewer;final String prompt,dimension;final AtomicBoolean permit=new AtomicBoolean(true);final List<Map<String,Object>> results=new ArrayList<>();String state="PLANNING",error="";long revision,expires=System.currentTimeMillis()+240000;GameCommandPlan plan;boolean waiting,observed,success;int dispatchedTick,index;long commandResult;Job(ServerPlayer p,UUID a,String text){viewer=p;agent=a;prompt=text;world=MineAgentRuntimeServices.worldId(p.level().getServer());dimension=p.level().dimension().identifier().toString();}}
    private PlayerCommandAgent(){}
    private static Map<UUID,Job> jobs(MinecraftServer s){return JOBS.computeIfAbsent(s,k->new HashMap<>());}
    private static boolean current(Job j){var s=j.viewer.level().getServer();return j.permit.get()&&s.isSameThread()&&s.getPlayerList().getPlayer(j.viewer.getUUID())==j.viewer&&j.viewer.isAlive()&&j.world.equals(MineAgentRuntimeServices.worldId(s))&&j.dimension.equals(j.viewer.level().dimension().identifier().toString())&&System.currentTimeMillis()<j.expires&&ServerTaskStart.allowed(j.viewer,j.agent);}
    private static void save(Job j)throws Exception{var s=j.viewer.level().getServer();var payload=new LinkedHashMap<String,Object>();payload.put("operation",j.operation);payload.put("world",j.world);payload.put("owner",j.viewer.getUUID());payload.put("agent",j.agent);payload.put("prompt",j.prompt);payload.put("state",j.state);payload.put("error",j.error);payload.put("plan",j.plan);payload.put("results",List.copyOf(j.results));payload.put("expires",j.expires);payload.put("nextIndex",j.index);payload.put("commandDispatched",j.waiting);try(var db=new SqliteRuntimeRepository(s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"))){if(!db.compareAndSet(j.world,"player_command_operations_v1",j.operation.toString(),j.revision,JSON.writeValueAsString(payload),System.currentTimeMillis()).accepted())throw new IllegalStateException("PLAYER_COMMAND_JOURNAL_CHANGED");j.revision++;}}
    private static void say(ServerPlayer p,String text){p.sendSystemMessage(Component.literal("[AI 指令] "+text));}
    public static Map<String,Object> observe(ServerPlayer p){var j=jobs(p.level().getServer()).get(p.getUUID());return j==null?Map.of("state","NONE"):Map.of("operation",j.operation,"state",j.state,"error",j.error,"results",List.copyOf(j.results));}
    public static void submit(ServerPlayer p,UUID agent,String prompt)throws Exception{
        var s=p.level().getServer();if(!s.isSameThread()||!ServerTaskStart.allowed(p,agent))throw new SecurityException("PLAYER_COMMAND_PERMISSION");if(prompt==null||prompt.isBlank()||prompt.length()>4096)throw new IllegalArgumentException("PLAYER_COMMAND_PROMPT");var prior=jobs(s).get(p.getUUID());if(prior!=null&&Set.of("PLANNING","REVIEW","EXECUTING").contains(prior.state))throw new IllegalStateException("PLAYER_COMMAND_BUSY");var j=new Job(p,agent,prompt);save(j);jobs(s).put(p.getUUID(),j);
        var source=p.createCommandSourceStack();var dispatcher=s.getCommands().getDispatcher();var context=new StringBuilder(GameCommandPlan.instructions()).append("\n玩家="+p.getGameProfile().name()+";维度="+j.dimension+";坐标="+p.position()+";主手="+p.getMainHandItem()+"\n真实可用命令及usage:\n");
        for(var node:dispatcher.getRoot().getChildren())if(node.canUse(source)&&!node.getName().equals("ai")){context.append('/').append(node.getName()).append('\n');if(Set.of("gamerule","enchant","give","item","scoreboard","time","weather").contains(node.getName()))for(String usage:dispatcher.getAllUsage(node,source,true)){if(context.length()+usage.length()>32000)break;context.append(node.getName()).append(' ').append(usage).append('\n');}}
        context.append("\n请求：").append(prompt);say(p,"正在生成指令建议；本次会调用已配置模型。生成不等于执行，勿重复发送。");
        MineAgentRuntimeServices.worker(s).planPresentation(MineAgentRuntimeServices.config(s),j.operation,context.toString(),j.permit::get).whenComplete((response,failure)->s.execute(()->{
            try{if(!current(j)||!j.state.equals("PLANNING")){j.state="CANCELLED";save(j);return;}if(failure!=null||response==null||!response.type().equals("model.result"))throw new IllegalStateException(dev.mineagent.runtime.core.config.ServiceCallBudget.responseError(response,"PLAYER_COMMAND_MODEL_FAILED"));j.plan=GameCommandPlan.parse(String.valueOf(response.payload().get("text")));validate(p,j.plan);j.state="REVIEW";j.expires=System.currentTimeMillis()+120000;save(j);say(p,j.plan.summary());for(String command:j.plan.commands())say(p,"/"+command);p.sendSystemMessage(Component.literal("[执行以上指令]").withStyle(st->st.withColor(0x88CC99).withClickEvent(new ClickEvent.RunCommand("/ai commands confirm "+j.operation))).append(Component.literal("  [取消]").withStyle(st->st.withClickEvent(new ClickEvent.RunCommand("/ai commands cancel "+j.operation)))));say(p,"使用你的当前游戏权限；不会借用服务器 OP。确认两分钟内有效；Esc 关闭聊天不表示批准。");
            }catch(Exception e){j.permit.set(false);j.state="FAILED";j.error=code(e);try{save(j);}catch(Exception ignored){}say(p,j.error+"；未执行指令，不自动重发模型请求。");}
        }));
    }
    private static void validate(ServerPlayer p,GameCommandPlan plan)throws Exception{var d=p.level().getServer().getCommands().getDispatcher();for(String command:plan.commands()){var parse=d.parse(command,p.createCommandSourceStack());Commands.validateParseResults(parse);if(parse.getContext().getLastChild().getCommand()==null)throw new IllegalArgumentException("PLAYER_COMMAND_INCOMPLETE");for(var c=parse.getContext();c!=null;c=c.getChild())for(var node:c.getNodes())if(node.getNode().getName().equals("ai"))throw new IllegalArgumentException("PLAYER_COMMAND_RECURSION");}}
    public static int decide(ServerPlayer p,UUID operation,boolean confirm){
        var j=jobs(p.level().getServer()).get(p.getUUID());
        if(j==null||!j.operation.equals(operation)||j.viewer!=p){say(p,"没有当前待确认指令；重启或重连不会恢复旧执行。");return 0;}
        if(!Set.of("PLANNING","REVIEW","EXECUTING").contains(j.state)||confirm&&j.state.equals("EXECUTING")){say(p,"原操作状态："+j.state+"；没有重复执行。");return 0;}
        try{
            if(!confirm){j.permit.set(false);j.state=j.waiting?"OUTCOME_UNKNOWN":"CANCELLED";save(j);say(p,"已停止后续指令；已开始动作不自动回滚或重放。");return 1;}
            if(!j.state.equals("REVIEW")||!current(j))throw new IllegalStateException("PLAYER_COMMAND_STALE");
            validate(p,j.plan);j.state="EXECUTING";save(j);say(p,"已批准，按 Tick 顺序执行；可点击原取消按钮停止后续指令。");return 1;
        }catch(Exception e){fail(j,e);return 0;}
    }
    private static void fail(Job j,Exception e){j.permit.set(false);j.state=j.waiting?"OUTCOME_UNKNOWN":"FAILED";j.error=code(e);try{save(j);}catch(Exception ignored){}say(j.viewer,j.error+"；不重放已开始的指令。");}
    private static void advance(Job j)throws Exception{
        var s=j.viewer.level().getServer();
        if(j.waiting){
            if(s.getTickCount()-j.dispatchedTick<2)return;
            if(!j.observed){if(s.getTickCount()-j.dispatchedTick<200)return;throw new IllegalStateException("PLAYER_COMMAND_CALLBACK_UNKNOWN");}
            j.results.add(Map.of("command",j.plan.commands().get(j.index),"callbackObserved",true,"success",j.success,"result",j.commandResult));j.waiting=false;j.index++;
            if(!j.success){j.state="FAILED";j.error="PLAYER_COMMAND_RESULT_NOT_SUCCESSFUL";}else if(j.index==j.plan.commands().size())j.state="COMPLETED";
            save(j);if(!j.state.equals("EXECUTING")){j.permit.set(false);say(j.viewer,j.state+" · 实际指令回执 "+j.results.size()+" 条；不自动补跑。");}return;
        }
        // Persist exact command index before invoking vanilla. Never conclude failure merely because a nested command queued its callback.
        validate(j.viewer,new GameCommandPlan(j.plan.summary(),List.of(j.plan.commands().get(j.index))));j.waiting=true;j.observed=false;j.success=true;j.commandResult=0;j.dispatchedTick=s.getTickCount();save(j);
        int index=j.index;var source=j.viewer.createCommandSourceStack().withCallback((success,result)->{if(j.state.equals("EXECUTING")&&j.waiting&&j.index==index){j.observed=true;j.success&=success;j.commandResult+=result;}});
        s.getCommands().performPrefixedCommand(source,j.plan.commands().get(j.index));
    }
    private static String code(Throwable e){String m=Objects.toString(e.getMessage(),"");return m.matches("[A-Z][A-Z0-9_]{1,80}")?m:"PLAYER_COMMAND_REJECTED";}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var values=JOBS.get(e.getServer());if(values==null)return;for(var j:List.copyOf(values.values())){if(!j.permit.get())continue;try{if(!current(j))throw new IllegalStateException("PLAYER_COMMAND_CONTEXT_CHANGED");if(j.state.equals("EXECUTING"))advance(j);}catch(Exception failure){fail(j,failure);}}}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p){var values=JOBS.get(p.level().getServer());var j=values==null?null:values.remove(p.getUUID());if(j!=null)j.permit.set(false);}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var values=JOBS.remove(e.getServer());if(values!=null)values.values().forEach(j->j.permit.set(false));}
}
