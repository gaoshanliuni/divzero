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
    private record Request(Key key,long epoch,String text){}
    private static final Map<MinecraftServer,BehaviorAuthority> ALL=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Key,Long> epochs=new java.util.concurrent.ConcurrentHashMap<>();private final Map<UUID,Request> requests=new java.util.concurrent.ConcurrentHashMap<>();
    public static BehaviorAuthority get(MinecraftServer s){return ALL.computeIfAbsent(s,k->new BehaviorAuthority());}
    public boolean current(ServerPlayer p,UUID agent,UUID request){var r=requests.get(request);var k=new Key(p.getUUID(),agent);return r!=null&&r.key.equals(k)&&r.epoch==epochs.getOrDefault(k,0L);}
    public void invalidate(ServerPlayer p,UUID agent){epochs.merge(new Key(p.getUUID(),agent),1L,Long::sum);}
    public void accepted(ServerPlayer p,UUID agent,UUID operation,String raw){
        var key=new Key(p.getUUID(),agent);var old=requests.get(operation);if(old!=null){if(!old.key.equals(key)||!old.text.equals(raw))throw new IllegalStateException("BEHAVIOR_REQUEST_REUSED");return;}
        long epoch=epochs.merge(key,1L,Long::sum);requests.put(operation,new Request(key,epoch,raw));
        if(!ServerTaskStart.allowed(p,agent))return;
        String text=raw.strip().replaceAll("[，。！!,.\\s]","").toLowerCase(Locale.ROOT);
        if(Set.of("停下什么也别做","停止所有行动","什么也别做","stop everything","stopeverything","停止","停下","stop").contains(text)){
            SkillRuntime.get(p.level().getServer()).stopAll(p,agent);
            MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).ifPresent(body->{body.controls().cancel();body.movementController().stop();body.stopUsingItem();});
            // This request itself must not later start a skill while elaborating its acknowledgement.
            epochs.merge(key,1L,Long::sum);p.sendSystemMessage(Component.translatableWithFallback("mineagent.behavior.stopped","已立即停止行动；不会自动重新开战。"));return;
        }
        if(Set.of("跟着我","跟随我","followme").contains(text)){
            var n=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id","owner_follow").put("expected_revision",0).put("actor","ai").put("dimension",p.level().dimension().identifier().toString()).put("target","$owner");
            SkillRuntime.get(p.level().getServer()).start(p,agent,operation,null,n,dev.mineagent.runtime.core.task.SkillSpec.Kind.FOLLOW,()->current(p,agent,operation)).whenComplete((v,e)->p.level().getServer().execute(()->p.sendSystemMessage(e==null?Component.translatableWithFallback("mineagent.behavior.follow","已切换为跟随。"):Component.literal("跟随未启动："+e.getMessage()))));
        }
    }
    public static boolean bodyTool(String name){return dev.mineagent.runtime.core.task.SkillTools.TOOLS.contains(name)||Set.of("control_agent_body","request_player_control","control_player_session","build_agent_path","start_world_task").contains(name);}
    private BehaviorAuthority(){}
}
