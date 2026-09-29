package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.*;

/** A request retains its arrival epoch even while queued. Old model replies cannot control a newer intent. */
public final class BehaviorAuthority {
    private record Key(UUID owner,UUID agent){}
    private record Request(Key key,String text){}
    private static final Map<MinecraftServer,BehaviorAuthority> ALL=Collections.synchronizedMap(new WeakHashMap<>());
    private final dev.mineagent.runtime.core.task.BehaviorIntentOrder<Key> order=new dev.mineagent.runtime.core.task.BehaviorIntentOrder<>();private final Map<UUID,Request> requests=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,java.util.concurrent.CompletableFuture<String>> localReplies=new java.util.concurrent.ConcurrentHashMap<>();
    public static BehaviorAuthority get(MinecraftServer s){return ALL.computeIfAbsent(s,k->new BehaviorAuthority());}
    public boolean current(ServerPlayer p,UUID agent,UUID request){return order.current(new Key(p.getUUID(),agent),request);}
    public boolean claim(ServerPlayer p,UUID agent,UUID request){return order.claim(new Key(p.getUUID(),agent),request);}
    public java.util.concurrent.CompletableFuture<String> localReply(UUID request){return localReplies.get(request);}
    public boolean playerRequested(ServerPlayer p,UUID agent,UUID operation){
        var request=requests.get(operation);if(request==null||!current(p,agent,operation))return false;String text=request.text.toLowerCase(Locale.ROOT);
        if(text.matches("(?s).*(不要接管|别接管|不要控制我|别控制我|讲解|解释一下|介绍一下|explain|do not take over|don't take over|do not control|don't control).*"))return false;
        var active=dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent.inspect(p);if(Boolean.TRUE.equals(active.get("active"))&&agent.equals(active.get("agent")))return true;
        return java.util.regex.Pattern.compile("(接管|操控|控制|托管)(我|本人)(的(身体|角色|玩家))?(?=$|[，,。.!！\\s]|去|来|开始|帮|种|钓|跟|走|跑)").matcher(text).find()||List.of("take over my body","take over my player","take over my character","control my player","control my character","autopilot my player").stream().anyMatch(text::contains);
    }

    public long revision(ServerPlayer p,UUID agent){return order.revision(new Key(p.getUUID(),agent));}
    public void invalidate(ServerPlayer p,UUID agent){order.invalidate(new Key(p.getUUID(),agent));}
    public void accepted(ServerPlayer p,UUID agent,UUID operation,String raw){
        var key=new Key(p.getUUID(),agent);var old=requests.get(operation);if(old!=null){if(!old.key.equals(key)||!old.text.equals(raw))throw new IllegalStateException("BEHAVIOR_REQUEST_REUSED");return;}
        String text=raw.strip().replaceAll("[，。！!,.\\s]","").toLowerCase(Locale.ROOT);
        boolean stop=Set.of("停下什么也别做","停止所有行动","什么也别做","stopeverything","停止","停下","stop").contains(text);
        boolean follow=Set.of("跟着我","跟随我","followme").contains(text);
        requests.put(operation,new Request(key,raw));order.accept(key,operation,stop||follow);
        if(!ServerTaskStart.allowed(p,agent))return;
        if(Set.of("停下什么也别做","停止所有行动","什么也别做","stop everything","stopeverything","停止","停下","stop").contains(text)){
            SkillRuntime.get(p.level().getServer()).stopAll(p,agent);
            MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).ifPresent(body->{body.controls().cancel();body.movementController().stop();body.stopUsingItem();});
            // This request itself must not later start a skill while elaborating its acknowledgement.
            order.invalidate(key);localReplies.put(operation,java.util.concurrent.CompletableFuture.completedFuture(Component.translatableWithFallback("mineagent.behavior.stopped","已立即停止行动；不会自动重新开战。").getString()));return;
        }
        if(Set.of("跟着我","跟随我","followme").contains(text)){
            var n=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id","follow_"+operation.toString().substring(0,8)).put("expected_revision",0).put("actor","ai").put("dimension",p.level().dimension().identifier().toString()).put("target","$owner");
            var reply=new java.util.concurrent.CompletableFuture<String>();localReplies.put(operation,reply);
            SkillRuntime.get(p.level().getServer()).start(p,agent,operation,null,n,dev.mineagent.runtime.core.task.SkillSpec.Kind.FOLLOW,()->current(p,agent,operation)).whenComplete((v,e)->p.level().getServer().execute(()->{if(e!=null)reply.completeExceptionally(e);else if(!current(p,agent,operation))reply.completeExceptionally(new IllegalStateException("SKILL_CONTEXT_CHANGED"));else reply.complete(Component.translatableWithFallback("mineagent.behavior.follow","已切换为跟随。").getString());}));
        }
    }
    public static boolean bodyTool(String name){return dev.mineagent.runtime.core.task.SkillTools.TOOLS.contains(name)&&!Set.of("inspect_skills","inspect_behavior").contains(name)||Set.of("control_agent_body","request_player_control","control_player_session","build_agent_path","start_world_task").contains(name);}
    private BehaviorAuthority(){}
}
