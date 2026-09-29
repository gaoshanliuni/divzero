package dev.mineagent.runtime.neoforge.skill;
import com.fasterxml.jackson.databind.ObjectMapper;import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.agent.body.BodyControlCoordinator;
import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent;
import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerPlayer;import net.minecraft.world.entity.Entity;import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Frames drive the authenticated local input controller; no server-side real-player movement/attack. */
public final class PlayerSkillActor implements SkillActor {
    private static final ObjectMapper JSON=new ObjectMapper();private final ServerPlayer player;private final UUID skill;private final Object level;private final net.minecraft.world.level.GameType mode;
    private final BodyControlCoordinator controls=new BodyControlCoordinator();private final NativeNavigationIntent navigation=new NativeNavigationIntent();private Vec3 destination,aim;private UUID navOperation=UUID.randomUUID(),selectionOperation;private int selectionSlot=-1;private net.minecraft.world.item.Item selectionWanted;private String lastAction="";
    private boolean sprinting;private ObjectNode motion;private String hand="";private UUID offhandOperation;private int offhandSlot=-1;
    public PlayerSkillActor(ServerPlayer player,UUID agent,UUID skill,String label)throws Exception{this.player=player;this.skill=skill;this.level=player.level();this.mode=player.gameMode.getGameModeForPlayer();AutonomousPlayerAgent.beginLocalSkill(player,agent,skill,label);}
    public ServerPlayer player(){return player;}public BodyControlCoordinator controls(){return controls;}
    public boolean current(){return player.level()==level&&player.gameMode.getGameModeForPlayer()==mode&&AutonomousPlayerAgent.localSkillActive(player,skill);}
    public boolean inputReady(){return current()&&!player.isPassenger()&&player.containerMenu==player.inventoryMenu&&AutonomousPlayerAgent.localSkillReady(player,skill);}
    private void send(UUID session,String action,UUID operation,Vec3 target,int slot,int entity,String pathAction){
        if(!current()||!controls.owns(session,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT))throw new IllegalStateException("PLAYER_SKILL_CONTROL_CHANGED");
        if(!action.equals("HOTBAR")&&selectionOperation!=null&&player.getMainHandItem().is(selectionWanted))selectionOperation=null;lastAction=action;ObjectNode frame=JSON.createObjectNode().put("action",action).put("operation",operation.toString()).put("slot",slot).put("entity",entity).put("pathAction",pathAction).put("sprinting",sprinting);frame.putArray("target").add(target.x).add(target.y).add(target.z);if(!hand.isEmpty())frame.put("hand",hand);if(!action.equals("MOVE")&&!action.equals("HALT")&&motion!=null)frame.set("motion",motion.deepCopy());if(action.equals("MOVE"))motion=frame.deepCopy();if(aim!=null)frame.putArray("aim").add(aim.x).add(aim.y).add(aim.z);AutonomousPlayerAgent.localSkillFrame(player,skill,frame,switch(action){case "MOVE"->"前往操作位置";case "BREAK"->"收获成熟作物";case "USE_BLOCK"->"操作目标方块";case "HOLD"->"使用手中装备";case "RELEASE"->"释放蓄力";case "ATTACK_ENTITY"->"攻击选定目标";case "HOTBAR"->"选择装备";case "USE_ONCE"->"使用手中物品";default->"等待下一步";});
    }
    public String move(UUID session,Vec3 target){if(destination==null){navigation.start(target,.2);destination=target;navOperation=UUID.randomUUID();}else if(destination.distanceToSqr(target)>.09){navigation.update(target);destination=target;}
        if(!inputReady())return "PAUSED_INPUT";var step=navigation.tick(player);if(step!=null)send(session,"MOVE",navOperation,navigation.waypoint(step),0,-1,step.action().name());else send(session,"HALT",navOperation,player.position(),0,-1,"");return navigation.status();}
    public void aim(UUID session,Vec3 target){aim=target;}
    public void sprint(UUID session,boolean enabled){sprinting=enabled;}
    public boolean select(UUID session,int slot){if(!inputReady())return false;if(selectionOperation==null||selectionSlot!=slot){selectionSlot=slot;selectionWanted=player.getInventory().getItem(slot).getItem();selectionOperation=UUID.randomUUID();}send(session,"HOTBAR",selectionOperation,player.position(),slot,-1,"");return slot<9&&player.getInventory().getSelectedSlot()==slot;}
    public boolean equipOffhand(UUID session,int slot){if(!inputReady())return false;if(offhandOperation==null||offhandSlot!=slot){offhandOperation=UUID.randomUUID();offhandSlot=slot;}send(session,"OFFHAND",offhandOperation,player.position(),slot,-1,"");return player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD);}
    public void useHand(UUID session,UUID op,net.minecraft.world.InteractionHand value){hand=value.name();try{useItem(session,op,true);}finally{hand="";}}
    public void breakBlock(UUID session,UUID op,BlockPos target){send(session,"BREAK",op,Vec3.atCenterOf(target),0,-1,"");}
    public void useBlock(UUID session,UUID op,BlockPos target){send(session,"USE_BLOCK",op,InteractionTargetResolver.topHit(player,target),0,-1,"");}
    public void useItem(UUID session,UUID op,boolean hold){send(session,hold?"HOLD":"USE_ONCE",op,aim==null?player.getEyePosition().add(player.getLookAngle().scale(4)):aim,0,-1,"");}
    public void releaseItem(UUID session,UUID op){send(session,"RELEASE",op,aim==null?player.getEyePosition():aim,0,-1,"");}
    public void attack(UUID session,UUID op,Entity entity){send(session,"ATTACK_ENTITY",op,entity.getEyePosition(),0,entity.getId(),"");}
    public void stop(UUID session){motion=null;sprinting=false;if(selectionOperation!=null&&player.getMainHandItem().is(selectionWanted))selectionOperation=null;lastAction="HALT";navigation.stop("CANCELLED");destination=null;aim=null;player.stopUsingItem();if(current()){var frame=JSON.createObjectNode().put("action","HALT").put("operation",UUID.randomUUID().toString());frame.putArray("target").add(player.getX()).add(player.getY()).add(player.getZ());AutonomousPlayerAgent.localSkillFrame(player,skill,frame,"等待下一步");}}
    public Map<String,Object> observation(){return Map.of("adapter","REAL_PLAYER_INPUT","entityId",player.getId(),"ready",inputReady(),"navigation",navigation.status(),"search",navigation.evidence(),"position",List.of(player.getX(),player.getY(),player.getZ()),"mainHand",player.getMainHandItem().toString(),"selectedSlot",player.getInventory().getSelectedSlot(),"frameAction",lastAction);}
    public void report(String reason,boolean complete){if(current())AutonomousPlayerAgent.localSkillSummary(player,skill,complete?"本次任务已完成，等待新指令":switch(reason){case "WAITING_FOR_CROP_GROWTH"->"等待作物成熟";case "WAITING_FOR_REAL_BITE"->"等待鱼咬钩";case "SEED_REQUIRED_FOR_REPLANT","SEED_NOT_READY"->"等待补种种子";case "INVENTORY_FULL"->"背包已满，等待整理";case "FOLLOW_DISTANCE_SATISFIED","FOLLOW_HYSTERESIS"->"保持跟随距离";case "WAYPOINT_DWELL"->"在路标处停留";case "WAITING_CHUNKS"->"等待区块加载";case "NEXT_CAST"->"准备下一次抛竿";default->"等待下一步";},complete);}
}
