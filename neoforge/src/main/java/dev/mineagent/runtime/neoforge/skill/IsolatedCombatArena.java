package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Isolated native training only. No normal player receives a PvP grant or a changed respawn policy. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class IsolatedCombatArena {
    public record Bounds(String id,int x,int z,int floor,int radius){
        public AABB space(){return new AABB(x-radius,floor-4,z-radius,x+radius+1,floor+24,z+radius+1);}
        public boolean contains(Vec3 at){return at.x>x-radius&&at.x<x+radius+1&&at.z>z-radius&&at.z<z+radius+1&&at.y>=floor-3&&at.y<floor+24;}
    }
    private record Participant(ServerPlayer owner,MineAgentPlayer body,Bounds arena,int team){}
    private static final Map<ServerPlayer,Participant> PARTICIPANTS=new WeakHashMap<>();
    private static final Set<ServerPlayer> ARMED=Collections.newSetFromMap(new IdentityHashMap<>());
    public static boolean enabled(){String mode=System.getProperty("mineagent.skillSmokeMode","");return Boolean.getBoolean("mineagent.skillSmoke")&&(mode.startsWith("selfplay_")||mode.startsWith("policy_")||mode.equals("evoker_timeline")||mode.equals("multi_attack"));}
    private static void require(ServerPlayer owner){if(!enabled()||!owner.level().getServer().isSameThread())throw new SecurityException("ISOLATED_TRAINING_ONLY");}
    public static void prepare(ServerPlayer owner,List<Bounds> arenas){
        require(owner);ServerLevel level=owner.level();
        // Generate the complete neighbourhood first: later feature generation must not repopulate cleared arenas.
        for(var a:arenas)for(int x=(a.x-a.radius-16)>>4;x<=(a.x+a.radius+16)>>4;x++)for(int z=(a.z-a.radius-16)>>4;z<=(a.z+a.radius+16)>>4;z++){level.getChunk(x,z);level.setChunkForced(x,z,true);}
        for(var a:arenas){
            for(int x=a.x-a.radius;x<=a.x+a.radius;x++)for(int z=a.z-a.radius;z<=a.z+a.radius;z++)for(int y=a.floor-3;y<=level.getMaxY();y++){
                boolean wall=x==a.x-a.radius||x==a.x+a.radius||z==a.z-a.radius||z==a.z+a.radius;
                var state=y<=a.floor||wall&&y<=a.floor+5?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState();var at=new BlockPos(x,y,z);
                if(!level.getBlockState(at).equals(state))level.setBlock(at,state,2);
            }
            var fullSpace=new AABB(a.x-a.radius,a.floor-3,a.z-a.radius,a.x+a.radius+1,level.getMaxY()+1,a.z+a.radius+1);
            for(var entity:level.getEntities((net.minecraft.world.entity.Entity)null,fullSpace,e->!(e instanceof ServerPlayer)))entity.discard();
        }
    }
    public static Map<String,Object> place(ServerPlayer owner,MineAgentPlayer body,Bounds arena,int team,Vec3 at){
        require(owner);if(!body.ownerPlayerId().equals(owner.getUUID())||!arena.contains(at))throw new IllegalArgumentException("TRAINING_SPAWN_SCOPE");
        var before=body.position();body.teleportTo(owner.level(),at.x,at.y,at.z,Set.of(),team==0?-90:90,0,true);body.setDeltaMovement(Vec3.ZERO);body.fallDistance=0;
        if(body.level()!=owner.level()||body.position().distanceToSqr(at)>.0001||!body.level().noCollision(body,body.getBoundingBox())||body.level().noCollision(body,body.getBoundingBox().move(0,-.08,0)))throw new IllegalStateException("TRAINING_SPAWN_NOT_CLEAR_AND_SUPPORTED");
        PARTICIPANTS.put(body,new Participant(owner,body,arena,team));
        var scoreboard=owner.level().getServer().getScoreboard();String teamName=teamName(owner,arena,team);var nativeTeam=scoreboard.getPlayerTeam(teamName);if(nativeTeam==null)nativeTeam=scoreboard.addPlayerTeam(teamName);nativeTeam.setAllowFriendlyFire(false);scoreboard.addPlayerToTeam(body.getScoreboardName(),nativeTeam);
        return Map.of("requested",at.toString(),"before",before.toString(),"actual",body.position().toString(),"clear",true,"supported",true,"arena",arena.id,"team",team);
    }
    public static boolean suppressRespawn(MineAgentPlayer body){return enabled()&&PARTICIPANTS.containsKey(body);}
    public static void arm(ServerPlayer owner,List<MineAgentPlayer> bodies){require(owner);for(var body:bodies){var p=PARTICIPANTS.get(body);if(p==null||p.owner!=owner)throw new SecurityException("TRAINING_OWNER");for(var other:bodies)if(other!=body){var q=PARTICIPANTS.get(other);if(q!=null&&p.team==q.team&&body.canHarmPlayer(other))throw new IllegalStateException("TRAINING_NATIVE_FRIENDLY_FIRE_NOT_DISABLED");}}ARMED.addAll(bodies);}
    public static boolean opponents(ServerPlayer actor,LivingEntity target){
        if(!enabled()||!(target instanceof ServerPlayer player))return false;var a=PARTICIPANTS.get(actor);var b=PARTICIPANTS.get(player);
        return a!=null&&b!=null&&a.owner==b.owner&&a.arena.equals(b.arena)&&a.team!=b.team&&actor.level()==target.level()
                &&a.arena.contains(actor.position())&&a.arena.contains(target.position());
    }
    public static boolean consent(ServerPlayer owner,UUID agent,ServerPlayer actor,ServerPlayer target,dev.mineagent.runtime.core.task.CombatPolicy policy){
        var a=PARTICIPANTS.get(actor);return ARMED.contains(actor)&&ARMED.contains(target)&&opponents(actor,target)&&a.owner==owner&&a.body.agentId().equals(agent)
                &&policy.engagement()!=dev.mineagent.runtime.core.task.CombatPolicy.Engagement.NONE
                &&target.isAlive()&&!target.isCreative()&&!target.isSpectator()&&!policy.excluded().contains(target.getUUID())
                &&dev.mineagent.runtime.neoforge.task.ServerTaskStart.allowed(owner,agent)
                &&actor.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.PVP)&&actor.canHarmPlayer(target);
    }
    private static String teamName(ServerPlayer owner,Bounds arena,int side){return "dzs"+UUID.nameUUIDFromBytes((owner.getUUID()+":"+arena.id+":"+side).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","").substring(0,13);}
    public static void retire(ServerPlayer owner,MineAgentPlayer body){require(owner);var participant=PARTICIPANTS.get(body);if(participant==null||participant.owner!=owner)throw new SecurityException("TRAINING_OWNER");try{SkillRuntime.get(owner.level().getServer()).stopAll(owner,body.agentId());}finally{dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.bodies(owner.level().getServer()).remove(body.agentId(),owner.getUUID(),false);var board=owner.level().getServer().getScoreboard();var team=board.getPlayerTeam(teamName(owner,participant.arena,participant.team));if(team!=null){if(board.getPlayersTeam(body.getScoreboardName())==team)board.removePlayerFromTeam(body.getScoreboardName(),team);if(team.getPlayers().isEmpty())board.removePlayerTeam(team);}PARTICIPANTS.remove(body);ARMED.remove(body);}}
    @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){PARTICIPANTS.entrySet().removeIf(entry->entry.getValue().owner.level().getServer()==event.getServer());ARMED.removeIf(body->body.level().getServer()==event.getServer());}
    private IsolatedCombatArena(){}
}
