package dev.mineagent.runtime.neoforge.skill;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Explicit target grants. Getting hurt never creates one; native PvP/team restrictions still apply. */
public final class PvpConsent {
    private record Key(UUID owner,UUID agent){}
    private static final Map<MinecraftServer,Map<Key,ServerPlayer>> GRANTS=new WeakHashMap<>();
    private PvpConsent(){}
    public static void fromChat(ServerPlayer owner,UUID agent,String message){
        if(!ServerTaskStart.allowed(owner,agent))return;
        var matches=owner.level().getServer().getPlayerList().getPlayers().stream().filter(target->target.level()==owner.level()&&!target.getUUID().equals(agent)&&target.isAlive())
            .filter(target->PvpIntent.namesTarget(message,List.of(target.getName().getString(),target.getGameProfile().name(),target.getUUID().toString()),target==owner)).toList();
        if(matches.size()==1)grantFromPanel(owner,agent,matches.getFirst());
    }
    /** Called only by authenticated built-in panel selection (or isolated native test fixture). */
    public static void grantFromPanel(ServerPlayer owner,UUID agent,ServerPlayer target){
        if(!owner.level().getServer().isSameThread()||!ServerTaskStart.allowed(owner,agent)||target.level()!=owner.level()||!target.isAlive()||target.getUUID().equals(agent))throw new SecurityException("COMBAT_PVP_TARGET_AUTHORIZATION");
        GRANTS.computeIfAbsent(owner.level().getServer(),s->new HashMap<>()).put(new Key(owner.getUUID(),agent),target);
    }
    public static void revoke(ServerPlayer owner,UUID agent){var grants=GRANTS.get(owner.level().getServer());if(grants!=null)grants.remove(new Key(owner.getUUID(),agent));}
    public static boolean allowed(ServerPlayer owner,UUID agent,ServerPlayer actor,ServerPlayer target,CombatPolicy rule){
        var grants=GRANTS.get(owner.level().getServer());return grants!=null&&grants.get(new Key(owner.getUUID(),agent))==target&&rule.engagement()==CombatPolicy.Engagement.SPECIFIED&&rule.target().equals(target.getUUID().toString())
            &&target!=actor&&target.isAlive()&&!target.isSpectator()&&!target.isCreative()&&target.level()==actor.level()&&owner.level().getServer().getPlayerList().getPlayer(target.getUUID())==target
            &&ServerTaskStart.allowed(owner,agent)&&actor.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.PVP)&&actor.canHarmPlayer(target)&&!rule.excluded().contains(target.getUUID());
    }
}
