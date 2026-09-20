package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import java.nio.file.*;
import java.util.*;
/** An isolated production game fixture; changes only its copied test world and the real test viewer. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class PlayerBodySmokeServer {
    public static volatile int requested=1,prepared,verified;public static volatile boolean killRequested;public static volatile String failure="";
    private static boolean initialized,placed,broken,jumped;private static UUID scenarioOperation;private static int preparingAt,setupScenario;private static final List<Map<String,Object>> results=new ArrayList<>();
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.playerBodySmoke")||!failure.isEmpty())return;var s=e.getServer();var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
        try{
            if(!initialized){s.getPlayerList().op(p.nameAndId());var l=s.overworld();for(var pos:BlockPos.betweenClosed(380,180,380,420,185,420))l.setBlockAndUpdate(pos,pos.getY()==180?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState());MineAgentRuntimeServices.bodies(s).createPersistentAt("身体助手",p.getUUID(),l,new net.minecraft.world.phys.Vec3(410.5,181,410.5));initialized=true;var dir=Files.createDirectories(s.getServerDirectory().resolve("player-body-smoke"));Files.writeString(dir.resolve("fixture.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("world",MineAgentRuntimeServices.worldId(s),"player",p.getUUID())));}
            if(setupScenario!=requested&&p.isAlive()){setupScenario=requested;prepared=0;scenarioOperation=null;preparingAt=s.getTickCount();p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.teleportTo(s.overworld(),400.5,181,400.5,Set.of(),0,0,true);p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);p.setHealth(20);p.getFoodData().setFoodLevel(20);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.COBBLESTONE,16));p.getInventory().setItem(1,new ItemStack(Items.DIAMOND_PICKAXE));p.getInventory().setItem(2,new ItemStack(Items.APPLE,3));p.getInventory().setSelectedSlot(0);p.inventoryMenu.broadcastChanges();}
            if(prepared==0&&s.getTickCount()-preparingAt>=20)prepared=setupScenario;
            if(requested==1){if(s.overworld().getBlockState(new BlockPos(400,181,402)).is(Blocks.COBBLESTONE))placed=true;if(placed&&s.overworld().getBlockState(new BlockPos(400,181,402)).isAir())broken=true;if(p.getY()>181.3)jumped=true;}
            if(killRequested){killRequested=false;p.kill(s.overworld());}
            var observation=PlayerBodyAgent.observe(p);String state=String.valueOf(observation.get("state"));if(Set.of("PREPARING","PLANNING","REVIEW","ARMING","RUNNING").contains(state))scenarioOperation=(UUID)observation.get("operation");if(scenarioOperation!=null&&scenarioOperation.equals(observation.get("operation"))&&verified<requested&&Set.of("COMPLETED","STOPPED","FAILED").contains(state)&&Boolean.TRUE.equals(observation.get("journalSaved"))){
                if(requested==1){if(!state.equals("COMPLETED")||!placed||!broken||!jumped||p.getX()>399.5||Math.abs(p.getYRot()-90)>1||Math.abs(p.getXRot())>1||p.getInventory().getSelectedSlot()!=2)throw new IllegalStateException("BODY_WORLD_EFFECTS:"+observation+" placed="+placed+" broken="+broken+" jumped="+jumped+" pos="+p.position()+" yaw="+p.getYRot()+" pitch="+p.getXRot()+" slot="+p.getInventory().getSelectedSlot());}
                else if(state.equals("FAILED"))throw new IllegalStateException("BODY_RUNTIME_FAILED:"+observation);
                var row=new LinkedHashMap<String,Object>(observation);row.put("scenario",requested);row.put("player",p.getUUID());row.put("position",List.of(p.getX(),p.getY(),p.getZ()));row.put("placed",placed);row.put("broken",broken);row.put("jumped",jumped);results.add(row);var root=Files.createDirectories(s.getServerDirectory().resolve("player-body-smoke"));Files.writeString(root.resolve("server.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(results));verified=requested;
            }
        }catch(Exception error){failure=error.toString();}
    }
}
