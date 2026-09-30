package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import java.util.*;
import java.util.concurrent.*;

/** Records only successful transitions within the native portal handler, never ordinary /tp. */
public final class PortalRouteObservations {
    public record Capture(ServerLevel level,KnownPortalRoutes.Scope scope,KnownPortalRoutes.Location entry,String block,UUID actor){}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"divzero-portal-observations");t.setDaemon(true);return t;});
    private PortalRouteObservations(){}
    public static Capture before(Entity entity){
        if(!(entity instanceof ServerPlayer p)||p.portalProcess==null||!p.portalProcess.isInsidePortalThisTick()||!WorldIdentityRuntime.ready(p.level().getServer()))return null;
        UUID owner,agent;
        if(p instanceof MineAgentPlayer ai){owner=ai.ownerPlayerId();agent=ai.agentId();}
        else {var session=AutonomousPlayerAgent.inspect(p);if(!Boolean.TRUE.equals(session.get("active"))||!(session.get("agent") instanceof UUID id))return null;owner=p.getUUID();agent=id;}
        var pos=p.portalProcess.getEntryPosition();if(!p.level().hasChunkAt(pos))return null;var block=p.level().getBlockState(pos).getBlock();
        if(!(block instanceof net.minecraft.world.level.block.Portal portal)||!p.portalProcess.isSamePortal(portal))return null;
        return new Capture(p.level(),new KnownPortalRoutes.Scope(MineAgentRuntimeServices.worldId(p.level().getServer()),owner,agent),new KnownPortalRoutes.Location(p.level().dimension().identifier().toString(),new SkillSpec.Point(pos.getX()+.5,pos.getY(),pos.getZ()+.5)),BuiltInRegistries.BLOCK.getKey(block).toString(),p.getUUID());
    }
    public static void after(Entity entity,Capture capture){
        if(capture==null)return;var server=capture.level.getServer();var arrived=server.getPlayerList().getPlayer(capture.actor);
        if(arrived==null||!arrived.isAlive()||arrived.level()==capture.level||!MineAgentRuntimeServices.worldId(server).equals(capture.scope.world()))return;
        var exit=new KnownPortalRoutes.Location(arrived.level().dimension().identifier().toString(),new SkillSpec.Point(arrived.getX(),arrived.getY(),arrived.getZ()));var database=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");long at=System.currentTimeMillis();
        CompletableFuture.runAsync(()->{try(var routes=new KnownPortalRoutes(database)){routes.observe(capture.scope,capture.entry,exit,capture.block,capture.actor,at);}catch(Exception error){MineAgentRuntimeMod.LOGGER.warn("Portal observation was not saved: {}",error.getClass().getSimpleName());}},IO);
    }
}
