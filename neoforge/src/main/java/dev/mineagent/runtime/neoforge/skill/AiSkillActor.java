package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.agent.body.BodyControlCoordinator;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.body.InteractionTargetResolver;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import java.util.*;

public final class AiSkillActor implements SkillActor {
    private final MineAgentPlayer body;private final Object level;private final net.minecraft.world.level.GameType mode;private long navigation=-1;private UUID owner,operation;private Vec3 destination;
    public AiSkillActor(MineAgentPlayer body){this.body=body;level=body.level();mode=body.gameMode.getGameModeForPlayer();}
    private List<dev.mineagent.runtime.core.task.SurfacePathfinder.PathStep> checkedRoute;
    public String moveTactically(UUID session,List<dev.mineagent.runtime.core.task.SurfacePathfinder.PathStep> route){require(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT);var controller=body.movementController();if(route!=checkedRoute||navigation!=controller.commandRevision()){if(!controller.followCheckedRoute(body,route))return "ROUTE_CHANGED";checkedRoute=route;navigation=controller.commandRevision();destination=null;}return controller.outcome();}
    public ServerPlayer player(){return body;}public BodyControlCoordinator controls(){return body.controls();}
    public boolean current(){return body.canAct()&&body.level()==level&&body.gameMode.getGameModeForPlayer()==mode&&MineAgentRuntimeServices.bodies(body.level().getServer()).body(body.agentId()).orElse(null)==body;}
    public boolean inputReady(){return current()&&body.containerMenu==body.inventoryMenu;}
    private void require(UUID session,dev.mineagent.runtime.api.agent.BodyDomain domain){if(!inputReady()||!controls().owns(session,domain))throw new IllegalStateException("SKILL_CONTROL_CHANGED");owner=session;}
    public String move(UUID session,Vec3 target){if(checkedRoute!=null){checkedRoute=null;navigation=-1;}require(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT);var c=body.movementController();if(navigation<0||c.commandRevision()!=navigation){c.movePreciselyTo(target);navigation=c.commandRevision();destination=target;}else if(destination==null||destination.distanceToSqr(target)>.09){c.updateTarget(navigation,target);destination=target;}return c.outcome();}
    public boolean recovering(){return body.movementController().recovering();}
    public boolean recover(UUID session,Vec3 target){require(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT);return body.movementController().recover(body,target);}
    public void aim(UUID session,Vec3 target){require(session,dev.mineagent.runtime.api.agent.BodyDomain.LOOK);body.aim(session,target,8);}
    public void aimImmediately(UUID session,Vec3 target){aim(session,target);body.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,target);}
    public void sprint(UUID session,boolean enabled){require(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT);body.setSprinting(enabled&&(body.getFoodData().getFoodLevel()>6||body.hasInfiniteMaterials()));}
    public void jump(UUID session){require(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT);if(body.movementController().target().isEmpty()){if(body.onGround())body.jumpFromGround();}else body.movementController().tacticalJump(body,session);}
    public void haltMotion(UUID session){checkedRoute=null;require(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT);if(navigation>=0)body.movementController().stopIfCurrent(navigation);body.setSprinting(false);navigation=-1;destination=null;}
    public boolean select(UUID session,int slot){require(session,dev.mineagent.runtime.api.agent.BodyDomain.INVENTORY);if(slot<0||slot>=36)return false;if(slot<9)body.getInventory().setSelectedSlot(slot);else body.getInventory().pickSlot(slot);body.inventoryMenu.broadcastChanges();return true;}
    public boolean equipOffhand(UUID session,int slot){require(session,dev.mineagent.runtime.api.agent.BodyDomain.INVENTORY);if(slot<0||slot>=36)return false;var old=body.getOffhandItem();body.setItemInHand(InteractionHand.OFF_HAND,body.getInventory().getItem(slot));body.getInventory().setItem(slot,old);body.inventoryMenu.broadcastChanges();return true;}
    private boolean child(UUID session,UUID op){require(session,dev.mineagent.runtime.api.agent.BodyDomain.MAIN_HAND);if(op.equals(operation))return false;if(operation!=null){body.abortMiningIfCurrent(operation);body.abortItemUseIfCurrent(operation);controls().releaseChild(operation);}if(!controls().claimChild(session,op,this::current,()->{body.abortMiningIfCurrent(op);body.abortItemUseIfCurrent(op);}))throw new IllegalStateException("SKILL_CHILD_BUSY");operation=op;return true;}
    public void breakBlock(UUID session,UUID op,BlockPos target){if(child(session,op)&&!body.beginMining(target,op))throw new IllegalStateException("SKILL_MINING_REJECTED");}
    public void useBlock(UUID session,UUID op,BlockPos target){var aim=InteractionTargetResolver.topHit(body,target);if(!body.isWithinBlockInteractionRange(target,0)||!InteractionTargetResolver.lineOfSight(body,body.getEyePosition(),aim,target,false))throw new IllegalStateException("SKILL_BLOCK_OUT_OF_REACH");if(!child(session,op))return;var hit=new BlockHitResult(aim,Direction.UP,target,false);boolean sneak=body.isShiftKeyDown();body.setShiftKeyDown(false);try{body.gameMode.useItemOn(body,body.level(),body.getMainHandItem(),InteractionHand.MAIN_HAND,hit);body.swing(InteractionHand.MAIN_HAND);}finally{body.setShiftKeyDown(sneak);}body.inventoryMenu.broadcastChanges();}
    public void useItem(UUID session,UUID op,boolean hold){var hand=hold&&!body.getMainHandItem().is(net.minecraft.world.item.Items.BOW)&&body.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;if(child(session,op)&&!body.beginTaskItemUse(op,hand))throw new IllegalStateException("SKILL_ITEM_USE_REJECTED");}
    public void useHand(UUID session,UUID op,InteractionHand hand){if(child(session,op)&&!body.beginTaskItemUse(op,hand))throw new IllegalStateException("SKILL_ITEM_USE_REJECTED");}
    public void releaseItem(UUID session,UUID op){require(session,dev.mineagent.runtime.api.agent.BodyDomain.MAIN_HAND);if(op.equals(operation)&&body.ownsItemUse(op)&&body.isUsingItem())body.releaseUsingItem();}
    public void attack(UUID session,UUID op,Entity entity){if(!entity.isAlive()||!body.isWithinAttackRange(body.getMainHandItem(),entity instanceof net.minecraft.world.entity.LivingEntity living?living.getHitbox():entity.getBoundingBox(),0)||!body.hasLineOfSight(entity)||body.getAttackStrengthScale(.5f)<.95f)return;if(!child(session,op))return;if(entity instanceof net.minecraft.world.entity.LivingEntity living)BoostRuntime.arm(body,living,op);if(!body.isWithinAttackRange(body.getMainHandItem(),entity.getBoundingBox(),0))return;body.attack(entity);body.swing(InteractionHand.MAIN_HAND);}
    public void stop(UUID session){if(owner!=null&&!owner.equals(session))return;checkedRoute=null;if(navigation>=0)body.movementController().stopIfCurrent(navigation);if(operation!=null){body.abortMiningIfCurrent(operation);body.abortItemUseIfCurrent(operation);controls().releaseChild(operation);}body.setSprinting(false);operation=null;navigation=-1;destination=null;owner=null;}
    public Map<String,Object> observation(){return Map.of("adapter","AI_SERVER_PLAYER","entityId",body.getId(),"navigation",body.movementController().outcome(),"movement",body.movementController().evidence());}
}
