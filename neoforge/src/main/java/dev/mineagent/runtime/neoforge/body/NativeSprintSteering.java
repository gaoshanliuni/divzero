package dev.mineagent.runtime.neoforge.body;
import dev.mineagent.runtime.core.task.TraversalSafety;
import net.minecraft.world.phys.Vec3;

/** Input adapter for the clientless player. Only yaw and native forward/strafe inputs are changed. */
public final class NativeSprintSteering {
    public static boolean apply(MineAgentPlayer p,Vec3 heading){
        if(!p.onGround()||p.getDeltaMovement().y>.05||!p.isSprinting()||p.isUsingItem()||p.isCrouching()||p.isInWater()||p.onClimbable()||p.horizontalCollision||heading.horizontalDistanceSqr()<.42)return false;
        var check=new NativeTraversalEvaluator(p);var at=p.position().add(heading.multiply(1,0,1).normalize().scale(.75));var node=check.closest(at);
        if(node==null||Math.abs(node.y()-p.getY())>.1||!check.clear(at,net.minecraft.world.entity.Pose.STANDING,false))return false;
        var steering=TraversalSafety.diagonal(heading.x,heading.z,p.getYRot());p.setYRot(steering.yaw());p.setYHeadRot(steering.yaw());
        inputs(p,steering.strafe(),steering.forward(),1);return true;
    }
    /** LocalPlayer 26.1.2: normalized keyboard vector, .98/use/sneak scaling, square-input correction. */
    public static void inputs(MineAgentPlayer p,double strafe,double forward,double amount){
        double length=Math.hypot(strafe,forward);if(length<1e-6){p.xxa=p.zza=0;return;}
        double x=strafe/length,z=forward/length,scale=.98*Math.min(1,amount);
        if(p.isUsingItem()&&!p.isPassenger())scale*=p.getUseItem().getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS,net.minecraft.world.item.component.UseEffects.DEFAULT).speedMultiplier();
        if(p.isCrouching())scale*=p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
        double squareDistance=1/Math.max(Math.abs(x),Math.abs(z));scale=Math.min(1,scale*squareDistance);
        p.xxa=(float)(x*scale);p.zza=(float)(z*scale);
    }
    private NativeSprintSteering(){}
}
