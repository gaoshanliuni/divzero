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
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void key(KeyMapping k,boolean down){((PlayerControlKeyAccess)k).mineagent$bodyDown(down);}
    private static void click(KeyMapping k){((PlayerControlKeyAccess)k).mineagent$bodyClicks(1);}
    public static void reset(){command="";clicked=false;doorCooldown=0;COMPLETED.clear();}
    public static Map<String,Object> observation(){var p=mc().player;return Map.of("command",command,"clicked",clicked,"deduplicatedOperations",COMPLETED.size(),"position",p==null?"":p.position().toString(),"mainHand",p==null?"":p.getMainHandItem().toString(),"hit",Objects.toString(mc().hitResult),"focused",mc().isWindowActive(),"mouseGrabbed",mc().mouseHandler.isMouseGrabbed());}
    public static void validate(JsonNode n){if(!n.isObject()||!Set.of("MOVE","HALT","HOTBAR","BREAK","USE_BLOCK","USE_ONCE","HOLD","RELEASE","ATTACK_ENTITY").contains(n.path("action").asText()))throw new IllegalArgumentException("SKILL_INPUT_FRAME");UUID.fromString(n.path("operation").asText());vector(n.path("target"));if(n.has("aim"))vector(n.get("aim"));if(n.has("slot")&&(!n.get("slot").isIntegralNumber()||n.get("slot").asInt()<0||n.get("slot").asInt()>35))throw new IllegalArgumentException("SKILL_INPUT_SLOT");}
    public static void tick(JsonNode n){
        validate(n);var mc=mc();var p=mc.player;if(p==null||mc.gameMode==null)return;String action=n.path("action").asText(),next=n.path("operation").asText()+"/"+action;
        if(!next.equals(command)){command=next;clicked=COMPLETED.contains(next);}var target=vector(n.path("target"));
        if(action.equals("HALT")){mc.gameMode.stopDestroyBlock();p.stopUsingItem();return;}
        if(action.equals("HOTBAR")){if(!clicked){int slot=n.path("slot").asInt();if(slot<9)p.getInventory().setSelectedSlot(slot);else if(p.containerMenu==p.inventoryMenu&&p.containerMenu.getCarried().isEmpty())mc.gameMode.handleContainerInput(p.inventoryMenu.containerId,slot,p.getInventory().getSelectedSlot(),ContainerInput.SWAP,p);markClicked();}return;}
        if(action.equals("RELEASE")){if(!clicked){if(p.isUsingItem())mc.gameMode.releaseUsingItem(p);markClicked();}return;}
        if(action.equals("MOVE")){
            if(doorCooldown>0)doorCooldown--;
            for(var pos:BlockPos.betweenClosed(BlockPos.containing(target),BlockPos.containing(target).above())){var s=p.level().getBlockState(pos);boolean closed=s.getBlock() instanceof DoorBlock d&&d.type().canOpenByHand()&&!s.getValue(DoorBlock.OPEN)||s.getBlock() instanceof FenceGateBlock&&!s.getValue(FenceGateBlock.OPEN);if(closed&&p.distanceToSqr(Vec3.atCenterOf(pos))<6.25){turn(Vec3.atCenterOf(pos));if(mc.hitResult instanceof BlockHitResult hit&&hit.getBlockPos().equals(pos)&&doorCooldown==0){click(mc.options.keyUse);doorCooldown=20;}return;}}
            var delta=target.subtract(p.position());double yaw=angle(delta.x,delta.z);turn(n.has("aim")?vector(n.get("aim")):target.add(0,p.getEyeHeight(),0));double relative=Math.toRadians(Mth.wrapDegrees((float)yaw-p.getYRot()));key(mc.options.keyUp,Math.cos(relative)>.35);key(mc.options.keyDown,Math.cos(relative)< -.35);key(mc.options.keyRight,Math.sin(relative)>.35);key(mc.options.keyLeft,Math.sin(relative)< -.35);
            String edge=n.path("pathAction").asText();key(mc.options.keyJump,edge.equals("CLIMB")&&delta.y>0||p.isInWater()&&delta.y>.1||delta.y>.65&&delta.horizontalDistanceSqr()<2);
            key(mc.options.keyShift,edge.equals("CROUCH")||p.isInWater()&&delta.y< -.2||!p.isInWater()&&dev.mineagent.runtime.neoforge.body.NativeSurfaceNavigation.requiresSneaking(p,target));return;
        }
        turn(target);
        boolean blockHit=mc.hitResult instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(BlockPos.containing(target));
        if(action.equals("BREAK")){if(blockHit){key(mc.options.keyAttack,true);if(!clicked){click(mc.options.keyAttack);markClicked();}}else mc.gameMode.stopDestroyBlock();return;}
        if(action.equals("USE_BLOCK")){if(blockHit&&!clicked){click(mc.options.keyUse);markClicked();}return;}
        if(action.equals("ATTACK_ENTITY")){if(!clicked&&p.getAttackStrengthScale(.5f)>=.95f&&mc.hitResult instanceof EntityHitResult hit&&hit.getEntity().getId()==n.path("entity").asInt()){click(mc.options.keyAttack);markClicked();}return;}
        if(action.equals("USE_ONCE")||action.equals("HOLD")){if(action.equals("HOLD"))key(mc.options.keyUse,true);if(!clicked&&aligned(target)){click(mc.options.keyUse);markClicked();}}
    }
    private static void markClicked(){clicked=true;COMPLETED.add(command);while(COMPLETED.size()>4096)COMPLETED.remove(COMPLETED.iterator().next());}
    private static boolean aligned(Vec3 target){var delta=target.subtract(mc().player.getEyePosition()).normalize();return delta.dot(mc().player.getLookAngle())>.995;}
    private static Vec3 vector(JsonNode n){if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("SKILL_INPUT_VECTOR");double[] v=new double[3];for(int i=0;i<3;i++)if(!n.get(i).isNumber()||!Double.isFinite(v[i]=n.get(i).asDouble())||Math.abs(v[i])>30_000_000)throw new IllegalArgumentException("SKILL_INPUT_VECTOR");return new Vec3(v[0],v[1],v[2]);}
    private static double angle(double x,double z){return Math.toDegrees(Math.atan2(-x,z));}
    private static void turn(Vec3 target){var p=mc().player;var d=target.subtract(p.getEyePosition());float yaw=(float)angle(d.x,d.z),pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));p.setYRot(p.getYRot()+Mth.clamp(Mth.wrapDegrees(yaw-p.getYRot()),-15,15));p.setXRot(Mth.clamp(p.getXRot()+Mth.clamp(pitch-p.getXRot(),-12,12),-90,90));}
    private NativeSkillInput(){}
}
