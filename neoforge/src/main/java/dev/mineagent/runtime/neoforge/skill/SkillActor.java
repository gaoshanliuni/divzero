package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.agent.body.BodyControlCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** The local skill loop is shared; only this boundary may execute physical input. */
public interface SkillActor {
    ServerPlayer player();BodyControlCoordinator controls();
    boolean current();boolean inputReady();
    String move(UUID session,Vec3 destination);void aim(UUID session,Vec3 target);
    void sprint(UUID session,boolean enabled);
    void jump(UUID session);
    boolean select(UUID session,int slot);
    boolean equipOffhand(UUID session,int slot);
    void breakBlock(UUID session,UUID operation,BlockPos target);
    void useBlock(UUID session,UUID operation,BlockPos target);
    void useItem(UUID session,UUID operation,boolean hold);
    void useHand(UUID session,UUID operation,net.minecraft.world.InteractionHand hand);
    void releaseItem(UUID session,UUID operation);
    void attack(UUID session,UUID operation,Entity entity);
    void stop(UUID session);Map<String,Object> observation();
    default void haltMotion(UUID session){stop(session);}
    default String moveTactically(UUID session,List<dev.mineagent.runtime.core.task.SurfacePathfinder.PathStep> route){return move(session,dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator.point(route.getFirst().to()));}
}
