package dev.mineagent.runtime.neoforge.client.body;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.neoforge.mixin.client.PlayerControlKeyAccess;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Executes only frames inside the existing local autonomy identity/lease boundary. */
public final class NativeSkillInput {
    private static String command="";private static boolean clicked;private static int doorCooldown;private static final Set<String> COMPLETED=new LinkedHashSet<>();
    private static int climbed,swam,crouched,moved,aimTick=-1,aimsThisTick,maxAimsPerTick,backwardTicks;private static Vec3 lastPosition;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void key(KeyMapping k,boolean down){AutonomyVirtualInput.key(k,down);}
    public static void reset(){command="";clicked=false;doorCooldown=0;COMPLETED.clear();climbed=swam=crouched=moved=0;aimTick=-1;aimsThisTick=maxAimsPerTick=backwardTicks=0;lastPosition=null;}
    public static Map<String,Object> motionEvidence(){return Map.of("climbingTicks",climbed,"swimmingTicks",swam,"crouchingTicks",crouched,"observedMovingTicks",moved,"maxAimUpdatesPerTick",maxAimsPerTick,"observedBackwardTicks",backwardTicks);}
    public static Map<String,Object> observation(){var p=mc().player;return Map.of("command",command,"clicked",clicked,"deduplicatedOperations",COMPLETED.size(),"position",p==null?"":p.position().toString(),"mainHand",p==null?"":p.getMainHandItem().toString(),"hit",Objects.toString(mc().hitResult),"focused",mc().isWindowActive(),"mouseGrabbed",mc().mouseHandler.isMouseGrabbed());}
    public static void validate(JsonNode n){if(!n.isObject()||!Set.of("MOVE","HALT","HALT_MOTION","HOTBAR","OFFHAND","BREAK","USE_BLOCK","USE_ONCE","HOLD","RELEASE","ATTACK_ENTITY","LOOK","JUMP","SNEAK").contains(n.path("action").asText()))throw new IllegalArgumentException("SKILL_INPUT_FRAME");UUID.fromString(n.path("operation").asText());vector(n.path("target"));if(n.has("aim"))vector(n.get("aim"));if(n.has("hand")&&!Set.of("MAIN_HAND","OFF_HAND").contains(n.get("hand").asText()))throw new IllegalArgumentException("SKILL_INPUT_HAND");if(n.has("motion")){var m=n.get("motion");if(m.has("motion")||!m.path("action").asText().equals("MOVE"))throw new IllegalArgumentException("SKILL_INPUT_MOTION");validate(m);}if(n.has("slot")&&(!n.get("slot").isIntegralNumber()||n.get("slot").asInt()<0||n.get("slot").asInt()>35))throw new IllegalArgumentException("SKILL_INPUT_SLOT");}
    public static void tick(JsonNode n){
        validate(n);if(n.has("motion")){var motion=(com.fasterxml.jackson.databind.node.ObjectNode)n.get("motion").deepCopy();if(n.has("aim"))motion.set("aim",n.get("aim"));else if(Set.of("HOLD","USE_ONCE","ATTACK_ENTITY","USE_BLOCK","BREAK").contains(n.path("action").asText()))motion.set("aim",n.get("target"));tick(motion);}var mc=mc();var p=mc.player;if(p==null||mc.gameMode==null)return;String action=n.path("action").asText(),next=n.path("operation").asText()+"/"+action;
        if(!next.equals(command)){command=next;clicked=COMPLETED.contains(next);}var target=vector(n.path("target"));
        if(action.equals("HALT_MOTION")){if(p.isUsingItem())((PlayerControlKeyAccess)mc.options.keyUse).mineagent$bodyDown(true);if(n.has("aim"))turn(vector(n.get("aim")));return;}
        if(action.equals("HALT")){mc.gameMode.stopDestroyBlock();p.stopUsingItem();return;}
        if(!n.has("motion"))key(mc.options.keyShift,n.path("sneaking").asBoolean());
        if(action.equals("JUMP")){key(mc.options.keyJump,true);return;}if(action.equals("SNEAK"))return;
        if(action.equals("OFFHAND")){if(!clicked&&p.containerMenu==p.inventoryMenu&&p.containerMenu.getCarried().isEmpty()){int slot=n.path("slot").asInt();mc.gameMode.handleContainerInput(p.inventoryMenu.containerId,slot<9?slot+36:slot,40,ContainerInput.SWAP,p);markClicked();}return;}
        if(action.equals("HOTBAR")){if(!clicked){int slot=n.path("slot").asInt();if(slot<9){p.getInventory().setSelectedSlot(slot);markClicked();}else if(p.containerMenu==p.inventoryMenu&&p.containerMenu.getCarried().isEmpty()){mc.gameMode.handleContainerInput(p.inventoryMenu.containerId,slot,p.getInventory().getSelectedSlot(),ContainerInput.SWAP,p);markClicked();}}return;}
        if(action.equals("RELEASE")){if(!clicked){if(p.isUsingItem())mc.gameMode.releaseUsingItem(p);markClicked();}return;}
        if(action.equals("MOVE")){
            if(n.path("sprinting").asBoolean()&&p.isUsingItem()&&!p.getUseItem().getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS,net.minecraft.world.item.component.UseEffects.DEFAULT).canSprint())p.stopUsingItem();
            if(p.onClimbable())climbed++;if(p.isInWater())swam++;if(p.isCrouching())crouched++;if(lastPosition!=null&&p.position().distanceToSqr(lastPosition)>.0001){moved++;var traveled=p.position().subtract(lastPosition);if(traveled.x*p.getLookAngle().x+traveled.z*p.getLookAngle().z<-.001)backwardTicks++;}lastPosition=p.position();
            if(doorCooldown>0)doorCooldown--;
            for(var pos:BlockPos.betweenClosed(BlockPos.containing(target),BlockPos.containing(target).above())){var s=p.level().getBlockState(pos);boolean closed=s.getBlock() instanceof DoorBlock d&&d.type().canOpenByHand()&&!s.getValue(DoorBlock.OPEN)||s.getBlock() instanceof FenceGateBlock&&!s.getValue(FenceGateBlock.OPEN);if(closed&&p.distanceToSqr(Vec3.atCenterOf(pos))<6.25){turn(Vec3.atCenterOf(pos));if(p.pick(p.blockInteractionRange(),0,false) instanceof BlockHitResult hit&&hit.getBlockPos().equals(pos)&&doorCooldown==0){mc.gameMode.useItemOn(p,net.minecraft.world.InteractionHand.MAIN_HAND,hit);doorCooldown=20;}return;}}
            var delta=target.subtract(p.position());double yaw=angle(delta.x,delta.z);if(n.has("aim"))turn(vector(n.get("aim")));else if(delta.horizontalDistanceSqr()>.25)turn(p.getEyePosition().add(new Vec3(delta.x,0,delta.z).normalize().scale(4)));double relative=Math.toRadians(Mth.wrapDegrees((float)yaw-p.getYRot()));boolean horizontal=delta.horizontalDistanceSqr()>.015;key(mc.options.keyUp,horizontal&&Math.cos(relative)>.35);key(mc.options.keyDown,horizontal&&Math.cos(relative)< -.35);key(mc.options.keyRight,horizontal&&Math.sin(relative)>.35);key(mc.options.keyLeft,horizontal&&Math.sin(relative)< -.35);
            String edge=n.path("pathAction").asText();key(mc.options.keyJump,edge.equals("CLIMB")&&delta.y>0||p.isInWater()&&delta.y>.1||delta.y>.65&&delta.horizontalDistanceSqr()<2);
            key(mc.options.keySprint,n.path("sprinting").asBoolean()||p.isInWater()&&(edge.equals("SWIM")||edge.equals("ENTER_WATER")));key(mc.options.keyShift,n.path("sneaking").asBoolean()||edge.equals("CROUCH")||p.isInWater()&&delta.y< -.2||!p.isInWater()&&dev.mineagent.runtime.neoforge.body.NativeSurfaceNavigation.requiresSneaking(p,target));return;
        }
        if(!n.has("motion"))turn(target);
        var hit=p.pick(p.blockInteractionRange(),0,false);boolean blockHit=hit instanceof BlockHitResult b&&b.getType()==HitResult.Type.BLOCK&&b.getBlockPos().equals(BlockPos.containing(target));
        if(action.equals("BREAK")){if(blockHit){var block=(BlockHitResult)hit;if(!clicked){mc.gameMode.startDestroyBlock(block.getBlockPos(),block.getDirection());markClicked();}else mc.gameMode.continueDestroyBlock(block.getBlockPos(),block.getDirection());p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);}else mc.gameMode.stopDestroyBlock();return;}
        if(action.equals("USE_BLOCK")){if(p.isUsingItem())((PlayerControlKeyAccess)mc.options.keyUse).mineagent$bodyDown(true);if(blockHit&&!clicked){var result=mc.gameMode.useItemOn(p,net.minecraft.world.InteractionHand.MAIN_HAND,(BlockHitResult)hit);if(!result.consumesAction())for(var hand:net.minecraft.world.InteractionHand.values())if(mc.gameMode.useItem(p,hand).consumesAction()||p.isUsingItem())break;if(p.isUsingItem())((PlayerControlKeyAccess)mc.options.keyUse).mineagent$bodyDown(true);p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);markClicked();}return;}
        if(action.equals("ATTACK_ENTITY")){if(p.isUsingItem())p.stopUsingItem();var entity=p.level().getEntity(n.path("entity").asInt());if(!clicked&&entity!=null&&entity.isAlive()&&aligned(target)&&p.isWithinAttackRange(p.getMainHandItem(),entity instanceof net.minecraft.world.entity.LivingEntity living?living.getHitbox():entity.getBoundingBox(),0)&&p.hasLineOfSight(entity)&&p.getAttackStrengthScale(.5f)>=.95f){mc.gameMode.attack(p,entity);p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);markClicked();}return;}
        if(action.equals("USE_ONCE")||action.equals("HOLD")){if(n.has("hand")&&p.isUsingItem()&&!p.getUsedItemHand().name().equals(n.get("hand").asText()))p.stopUsingItem();if(action.equals("HOLD"))((PlayerControlKeyAccess)mc.options.keyUse).mineagent$bodyDown(true);if(!clicked&&aligned(target)){for(var hand:n.has("hand")?new net.minecraft.world.InteractionHand[]{net.minecraft.world.InteractionHand.valueOf(n.get("hand").asText())}:net.minecraft.world.InteractionHand.values())if(mc.gameMode.useItem(p,hand).consumesAction()||p.isUsingItem())break;markClicked();}}
    }
    private static void markClicked(){clicked=true;COMPLETED.add(command);while(COMPLETED.size()>4096)COMPLETED.remove(COMPLETED.iterator().next());}
    private static boolean aligned(Vec3 target){var delta=target.subtract(mc().player.getEyePosition()).normalize();return delta.dot(mc().player.getLookAngle())>.995;}
    private static Vec3 vector(JsonNode n){if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("SKILL_INPUT_VECTOR");double[] v=new double[3];for(int i=0;i<3;i++)if(!n.get(i).isNumber()||!Double.isFinite(v[i]=n.get(i).asDouble())||Math.abs(v[i])>30_000_000)throw new IllegalArgumentException("SKILL_INPUT_VECTOR");return new Vec3(v[0],v[1],v[2]);}
    private static double angle(double x,double z){return Math.toDegrees(Math.atan2(-x,z));}
    private static void turn(Vec3 target){var p=mc().player;if(aimTick!=p.tickCount){aimTick=p.tickCount;aimsThisTick=0;}maxAimsPerTick=Math.max(maxAimsPerTick,++aimsThisTick);var d=target.subtract(p.getEyePosition());float yaw=(float)angle(d.x,d.z),pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));p.setYRot(p.getYRot()+Mth.clamp(Mth.wrapDegrees(yaw-p.getYRot()),-15,15));p.setXRot(Mth.clamp(p.getXRot()+Mth.clamp(pitch-p.getXRot(),-12,12),-90,90));}
    private NativeSkillInput(){}
}
