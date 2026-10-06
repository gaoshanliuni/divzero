package dev.mineagent.runtime.neoforge.skill;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import java.nio.file.*;
import java.util.*;

/** Real client useItemOn, selected-slot prediction, stop/restart and cleanup cancellation. */
final class ModernLifecycleVerification {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final List<Object> results=new ArrayList<>();private static int phase,rounds,started;private static UUID request,insideDrop,outsideDrop;private static boolean done;
    static boolean tick(MinecraftServer server){
        if(done)return false;var s=NativeHumanDuel.fixtureState(server);if(s==null)return false;var p=s.player();int now=server.getTickCount();
        try{
            if(phase>0&&now-started>700)throw new IllegalStateException("LIFECYCLE_TIMEOUT_"+phase+"_"+s.phase());
            if(phase==0){var path=server.getServerDirectory().resolve("modern-loadout-client.json");if(!Files.isRegularFile(path))return false;var ui=JSON.readTree(Files.readString(path));require(ui.path("status").asText().equals("PASS"),"LOADOUT_UI_FAILED");results.add(ui);send(p,"ready",0,new BlockPos(-4,100,800));phase=1;started=now;return false;}
            if(phase==1&&s.phase().equals("FIGHTING")&&s.ai()!=null){
                SkillRuntime.cancelForBody(p,s.ai().agentId());p.setInvulnerable(true);
                require(p.getInventory().getItem(3).is(Items.ENDER_PEARL)&&p.getInventory().getItem(3).getCount()==16&&p.getInventory().getItem(2).is(Items.GOLDEN_APPLE)&&s.ai().getInventory().getItem(3).is(Items.SHEARS),"UI_EQUIPMENT_NOT_APPLIED");
                require(p.level().getBlockState(new BlockPos(-4,101,800)).isAir(),"RESTART_DID_NOT_CLEAR_OLD_WOOL");
                if(rounds==1){require(p.level().getEntity(insideDrop)==null&&p.level().getEntity(outsideDrop)!=null,"DROP_CLEANUP_SCOPE");results.add(Map.of("insideDropRemoved",true,"outsideDropPreserved",true));}
                send(p,"place",0,new BlockPos(-4,100,800));phase=2;started=now;
            }else if(phase==2){var ack=ack(p);if(ack!=null){checkPlace(p,ack,new BlockPos(-4,101,800),true,63);rounds++;results.add(Map.of("round",rounds,"placement",ack));send(p,"stop",1,new BlockPos(1,100,766));phase=3;started=now;}}
            else if(phase==3&&s.phase().equals("STOPPED")){
                if(rounds==4){send(p,"hold",1,new BlockPos(1,100,766));phase=7;started=now;return false;}
                if(rounds==1){insideDrop=drop(p,10.5,805.5);outsideDrop=drop(p,10.5,776.5);}
                send(p,"ready",1,new BlockPos(1,100,766));phase=rounds==1?4:1;started=now;
            }else if(phase==4&&s.cleaning()){
                send(p,"place",1,new BlockPos(1,100,766));phase=5;started=now;
            }else if(phase==5){var ack=ack(p);if(ack!=null){checkPlace(p,ack,new BlockPos(1,101,766),false,63);results.add(Map.of("cleanupRejectedPlacement",ack));require(s.cleaning(),"CLEANUP_CANCELLATION_WINDOW_MISSED");send(p,"stop",1,new BlockPos(1,100,766));phase=6;started=now;}}
            else if(phase==6&&s.phase().equals("STOPPED")){require(!s.cleaning(),"CLEANUP_CONTINUED_AFTER_STOP");send(p,"ready",1,new BlockPos(-4,100,800));phase=1;started=now;}
            else if(phase==7){var ack=ack(p);if(ack!=null){require(p.isUsingItem()&&ack.path("using").asBoolean(),"PRE_DEATH_NATIVE_USE_REQUIRED");p.setInvulnerable(false);p.hurtServer(p.level(),p.damageSources().genericKill(),1000);send(p,"respawn",2,new BlockPos(1,100,766));phase=8;started=now;}}
            else if(phase==8){var ack=ack(p);if(ack!=null&&p.isAlive()){require(ack.path("alive").asBoolean()&&!ack.path("using").asBoolean()&&!p.isUsingItem(),"RESPAWN_USE_STATE");require(p.getInventory().getSelectedSlot()==ack.path("selected").asInt(),"RESPAWN_SELECTED_SLOT");require(p.getInventory().getItem(2).getCount()==3&&ack.path("supply").asInt()==3,"DEATH_CONSUMED_UNFINISHED_FOOD");results.add(Map.of("nativeRespawn",ack));finish(p,"PASS","");return true;}}
        }catch(Throwable error){finish(p,"FAILED",error.toString());return true;}
        return false;
    }
    private static UUID drop(ServerPlayer p,double x,double z){var item=new net.minecraft.world.entity.item.ItemEntity(p.level(),x,103,z,new net.minecraft.world.item.ItemStack(Items.DIAMOND));item.setNoGravity(true);item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);item.setPickUpDelay(32767);p.level().addFreshEntity(item);return item.getUUID();}
    private static void send(ServerPlayer p,String kind,int selected,BlockPos floor){request=UUID.randomUUID();try{var payload=JSON.writeValueAsString(Map.of("id",request.toString(),"kind",kind,"selected",selected,"x",floor.getX(),"y",floor.getY(),"z",floor.getZ()));net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new dev.mineagent.runtime.neoforge.network.UiPayloads.Event(request,"modernPlacementFixture",payload));}catch(Exception e){throw new IllegalStateException(e);}}
    private static com.fasterxml.jackson.databind.JsonNode ack(ServerPlayer p)throws Exception{var path=p.level().getServer().getServerDirectory().resolve("modern-placement-client.json");if(!Files.isRegularFile(path))return null;var data=JSON.readTree(Files.readString(path));if(!data.path("id").asText().equals(request.toString()))return null;require(data.path("error").asText().isEmpty(),data.path("error").asText());return data;}
    private static void checkPlace(ServerPlayer p,com.fasterxml.jackson.databind.JsonNode ack,BlockPos target,boolean exists,int count){require(p.level().getBlockState(target).is(Blocks.WHITE_WOOL)==exists,"SERVER_BLOCK_MISMATCH");require(ack.path("wool").asBoolean()==exists,"CLIENT_BLOCK_MISMATCH");require(p.getInventory().getItem(1).getCount()==count&&ack.path("count").asInt()==count,"NATIVE_PREDICTION_COUNT_MISMATCH");require(p.getInventory().getSelectedSlot()==ack.path("selected").asInt(),"SELECTED_SLOT_MISMATCH");}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void finish(ServerPlayer p,String status,String error){done=true;try{Files.writeString(p.level().getServer().getServerDirectory().resolve("modern-lifecycle-fixture.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source","FIXTURE_ONLY","status",status,"error",error,"phase",phase,"placements",rounds,"results",results)));}catch(Exception e){throw new IllegalStateException(e);}NativeHumanDuel.command(p,"stop");}
    private ModernLifecycleVerification(){}
}
