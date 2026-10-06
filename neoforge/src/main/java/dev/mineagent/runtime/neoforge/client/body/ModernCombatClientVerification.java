package dev.mineagent.runtime.neoforge.client.body;
import net.minecraft.client.Minecraft;
import com.google.gson.JsonObject;
/** Native client inputs confined to the deliberately launched automatic combat fixture. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ModernCombatClientVerification {
    private static int entity=-1,attempts;private static boolean chase,attack,motion;private static int motionTicks,leftTicks,rightTicks,jumpTicks;
    public static void accept(JsonObject value){if(!Boolean.getBoolean("mineagent.modernCombatFixture"))return;entity=value.get("entity").getAsInt();chase=value.get("chase").getAsBoolean();attack=value.get("attack").getAsBoolean();motion=value.has("motion")&&value.get("motion").getAsBoolean();motionTicks=0;}
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.modernCombatFixture"))return;var mc=Minecraft.getInstance();var p=mc.player;
        if(p==null||mc.level==null||mc.gameMode==null)return;
        if(mc.screen!=null&&mc.screen.getClass().getName().contains("PvpMapClient$Loadouts"))mc.setScreen(null);
        var target=mc.level.getEntity(entity);boolean active=target!=null&&target.isAlive()&&p.isAlive();
        mc.options.keyUp.setDown(active&&chase&&p.distanceTo(target)>1.25);mc.options.keySprint.setDown(active&&chase);
        mc.options.keyLeft.setDown(active&&motion&&motionTicks%60<30);mc.options.keyRight.setDown(active&&motion&&motionTicks%60>=30);mc.options.keyJump.setDown(active&&motion);
        if(active&&motion){motionTicks++;if(motionTicks%60<30)leftTicks++;else rightTicks++;if(!p.onGround())jumpTicks++;p.setYRot(0);p.setXRot(0);if(motionTicks%10==0)try{java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("modern-motion-client.json"),new com.google.gson.Gson().toJson(java.util.Map.of("source","FIXTURE_ONLY","leftTicks",leftTicks,"rightTicks",rightTicks,"airborneTicks",jumpTicks)));}catch(Exception e){throw new IllegalStateException(e);}return;}
        if(!active||!chase)return;var d=target.getEyePosition().subtract(p.getEyePosition());p.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));p.setXRot((float)-Math.toDegrees(Math.atan2(d.y,d.horizontalDistance())));
        if(attack&&dev.mineagent.runtime.neoforge.skill.NativeAttackReadiness.ready(p)&&p.hasLineOfSight(target)&&p.isWithinAttackRange(p.getMainHandItem(),target.getBoundingBox(),0)){mc.gameMode.attack(p,target);p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);attempts++;}
        if(p.tickCount%20==0)try{java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("modern-combat-client.json"),"{\"source\":\"FIXTURE_ONLY\",\"nativeAttackAttempts\":"+attempts+"}");}catch(Exception e){throw new IllegalStateException(e);}
    }
    private ModernCombatClientVerification(){}
}
