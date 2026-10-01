package dev.mineagent.runtime.neoforge.client.body;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.TerrainProvenance;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;

/** Isolated regression for delayed identity and reimporting a template over a previously bound path. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class WorldStartupSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();private static boolean busy,done,placed;private static int ticks;
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        String mode=System.getProperty("mineagent.startupIdentitySmoke","");if(!Set.of("template","pending").contains(mode)||done)return;
        var mc=Minecraft.getInstance();if(mc.player==null||mc.getSingleplayerServer()==null||++ticks<100||busy)return;busy=true;UUID owner=mc.player.getUUID();
        mc.getSingleplayerServer().submit(()->{
            var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(owner);var history=TerrainProvenance.get(server);
            if(mode.equals("template")){
                if(!WorldIdentityRuntime.ready(server))throw new IllegalStateException("TEMPLATE_IDENTITY_STILL_PENDING");
                if(!history.available())return Map.<String,Object>of("waiting",true);
                try{var fixture=JSON.readTree(Files.readString(server.getServerDirectory().resolve("startup-fixture.json")));UUID old=UUID.fromString(fixture.path("oldScope").asText());UUID now=MineAgentRuntimeServices.worldId(server);if(old.equals(now))throw new IllegalStateException("OLD_TEMPLATE_SCOPE_REUSED");
                    try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));var q=db.createStatement();var r=q.executeQuery("SELECT world,value FROM mineagent_startup_fixture")){if(!r.next()||!r.getString(1).equals(old.toString())||!r.getString(2).equals("preserve original")||r.next())throw new IllegalStateException("OLD_ROWS_CHANGED");}
                    return Map.<String,Object>of("status","PASS","mode",mode,"newIdentity",true,"oldDataRetained",true,"terrainReady",true);
                }catch(Exception e){throw new IllegalStateException(e);}
            }
            if(WorldIdentityRuntime.ready(server)||history.available())throw new IllegalStateException("UNRESOLVED_WORLD_SERVICE_STARTED");
            var floor=new BlockPos(1,100,0);if(!placed){p.level().getChunk(floor);p.level().setBlock(floor,Blocks.STONE.defaultBlockState(),3);p.teleportTo(p.level(),.5,101,.5,Set.of(),-90,0,true);p.getInventory().setSelectedSlot(0);p.getInventory().setItem(0,new ItemStack(Items.WHITE_WOOL,2));p.gameMode.useItemOn(p,p.level(),p.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(1.5,101,.5),Direction.UP,floor,false));placed=true;}
            if(!p.level().getBlockState(floor.above()).is(Blocks.WHITE_WOOL)||!history.knownPlaced(p.level(),floor.above()))throw new IllegalStateException("PENDING_PLACEMENT_NOT_RETAINED");
            return Map.<String,Object>of("status","PASS","mode",mode,"worldPlayable",true,"terrainDeferred",true,"pendingPlacementRecorded",true);
        }).whenComplete((result,error)->mc.execute(()->{
            busy=false;if(error==null&&Boolean.TRUE.equals(result.get("waiting"))&&ticks<500)return;
            done=true;try{Files.writeString(mc.gameDirectory.toPath().resolve("world-startup-smoke.json"),JSON.writeValueAsString(error==null&&!result.containsKey("waiting")?result:Map.of("status","FAILED","mode",mode,"error",error==null?"TERRAIN_LOAD_TIMEOUT":error.toString())));}catch(Exception e){throw new IllegalStateException(e);}mc.stop();
        }));
    }
    private WorldStartupSmokeClient(){}
}
