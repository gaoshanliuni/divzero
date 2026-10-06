package dev.mineagent.runtime.neoforge.skill;
import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.core.task.PvpMapProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Actual controller/consumption combat checks in an explicitly isolated world. */
final class ModernCombatVerification {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Map<String,Object> proof=new LinkedHashMap<>();
    private static int phase,started,ammo,pearls,bowFrames;private static float playerHealth,aiHealth;private static Vec3 pearlOrigin;private static boolean done;
    private static Map<String,Object> skills(ServerPlayer p,MineAgentPlayer ai){return SkillRuntime.get(p.level().getServer()).snapshot(p,ai.agentId());}
    static boolean tick(ServerPlayer p,MineAgentPlayer ai){
        if(done)return false;int now=p.level().getServer().getTickCount();
        try{
            if(now%10==0)Files.writeString(p.level().getServer().getServerDirectory().resolve("modern-combat-progress.json"),JSON.writeValueAsString(Map.of("phase",phase,"tick",now,"aiHealth",ai.getHealth(),"humanHealth",p.getHealth(),"held",ai.getMainHandItem().toString(),"position",ai.position().toString(),"pearls",ai.getInventory().countItem(Items.ENDER_PEARL),"skills",skills(p,ai))));
            if(phase==0){
                p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);p.setHealth(80);p.setInvulnerable(false);
                var profile=PvpMapProfile.defaults().gear(0,"ai","secondary","minecraft:bow");PvpMapSupport.apply(ai,profile.ai());
                place(ai,new Vec3(8.5,101,800.5));place(p,new Vec3(-8.5,101,800.5));ammo=ai.getInventory().countItem(Items.ARROW);playerHealth=p.getHealth();phase=1;started=now;client(p,ai,false,false);
            }else if(phase==1){
                if(ai.getMainHandItem().is(Items.BOW))bowFrames++;
                if(ai.getInventory().countItem(Items.ARROW)<=ammo-2&&p.getHealth()<playerHealth){
                    require(bowFrames>0,"BACKUP_BOW_NOT_SELECTED");proof.put("backupBowNativeArrows",ammo-ai.getInventory().countItem(Items.ARROW));proof.put("backupBowHealthDamage",playerHealth-p.getHealth());proof.put("backupBowFrames",bowFrames);
                    place(p,ai.position().add(-1.4,0,0));playerHealth=p.getHealth();phase=2;started=now;
                }else require(now-started<360,"BACKUP_BOW_DID_NOT_FIRE_AND_HIT");
            }else if(phase==2){
                if(ai.getMainHandItem().is(Items.DIAMOND_SWORD)&&p.getHealth()<playerHealth){
                    proof.put("switchedToNativeMelee",true);proof.put("meleeHealthDamage",playerHealth-p.getHealth());
                    var profile=PvpMapProfile.defaults().gear(0,"ai","secondary","minecraft:ender_pearl");PvpMapSupport.apply(ai,profile.ai());
                    place(ai,new Vec3(8.5,101,800.5));place(p,new Vec3(-8.5,101,800.5));aiHealth=ai.getHealth();pearlOrigin=ai.position();pearls=ai.getInventory().countItem(Items.ENDER_PEARL);phase=3;started=now;
                }else require(now-started<200,"CLOSE_CONTACT_WEAPON_SWITCH");
            }else if(phase==3){
                if(ai.getInventory().countItem(Items.ENDER_PEARL)<pearls&&ai.position().distanceTo(pearlOrigin)>4&&ai.getHealth()<aiHealth){
                    require(pearls-ai.getInventory().countItem(Items.ENDER_PEARL)==1,"PEARL_REPLAY");proof.put("pearlNativeConsumption",1);proof.put("pearlTeleportDistance",ai.position().distanceTo(pearlOrigin));proof.put("pearlNativeDamage",aiHealth-ai.getHealth());proof.put("pearlSkills",skills(p,ai));
                    var profile=PvpMapProfile.defaults().gear(0,"ai","mainhand","minecraft:bow");PvpMapSupport.apply(ai,profile.ai());ai.setHealth(ai.getMaxHealth());place(ai,new Vec3(8.5,101,800.5));place(p,new Vec3(7.1,101,800.5));ammo=ai.getInventory().countItem(Items.ARROW);phase=4;started=now;client(p,ai,true,false);
                }else require(now-started<220,"PEARL_NATIVE_TELEPORT_NOT_CONFIRMED");
            }else if(phase==4){
                if(ai.getInventory().countItem(Items.ARROW)<ammo){
                    proof.put("pursuedArcherFiredAfterTicks",now-started);proof.put("pursuedArcherSkills",skills(p,ai));client(p,ai,false,false);
                    PvpMapSupport.apply(ai,PvpMapProfile.defaults().ai());ai.setHealth(ai.getMaxHealth());place(ai,new Vec3(6.5,101,800.5));place(p,new Vec3(5.1,101,800.5));aiHealth=ai.getHealth();playerHealth=p.getHealth();phase=5;started=now;client(p,ai,true,true);
                }else require(now-started<220,"PURSUED_ARCHER_NEVER_RETURNED_FIRE");
            }else if(phase==5&&now-started>=160){
                require(ai.isAlive(),"MELEE_PRESSURE_DEATH");proof.put("pressureAiHealth",ai.getHealth());proof.put("pressureHumanHealth",p.getHealth());proof.put("pressureSkills",skills(p,ai));
                require(ai.getHealth()<aiHealth&&p.getHealth()<playerHealth,"PRESSURE_NATIVE_DAMAGE_AND_COUNTER_REQUIRED");finish(p,ai,"PASS","");return true;
            }
        }catch(Throwable error){finish(p,ai,"FAILED",error.toString());return true;}
        return false;
    }
    private static void client(ServerPlayer p,MineAgentPlayer ai,boolean chase,boolean attack){
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new dev.mineagent.runtime.neoforge.network.UiPayloads.Event(UUID.randomUUID(),"modernCombatFixture","{\"entity\":"+ai.getId()+",\"chase\":"+chase+",\"attack\":"+attack+"}"));
    }
    private static void place(ServerPlayer p,Vec3 at){p.teleportTo(p.level(),at.x,at.y,at.z,Set.of(),0,0,true);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.setOnGround(true);}
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    private static void finish(ServerPlayer p,MineAgentPlayer ai,String status,String error){
        done=true;client(p,ai,false,false);proof.put("source","FIXTURE_ONLY");proof.put("status",status);proof.put("error",error);proof.put("phase",phase);proof.put("finalSkills",skills(p,ai));proof.put("aiBaseMovement",ai.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue());proof.put("aiMaxHealth",ai.getMaxHealth());
        try{Files.writeString(p.level().getServer().getServerDirectory().resolve("modern-combat-fixture.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof));}catch(Exception e){throw new IllegalStateException(e);}NativeHumanDuel.command(p,"stop");
    }
    private ModernCombatVerification(){}
}
