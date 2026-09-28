package dev.mineagent.runtime.neoforge.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ConversationAgentTools;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Explicit isolated zero-model acceptance of the production building tools and actual blocks. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ComponentBuildingSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();private static final List<Object> receipts=new ArrayList<>();
    private static int ticks,stage;private static boolean busy,done;private static UUID agent;private static BlockPos origin;private static String roofBaseline;
    private static JsonNode source;private static CompletableFuture<Map<String,Object>> background;
    private static ObjectNode args(long revision){return JSON.createObjectNode().put("id","home").put("revision",revision);}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static Path output()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("component-building-smoke"));}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.componentBuildingSmoke")||done)return;var mc=Minecraft.getInstance();
        try{
            if(++ticks>9000)throw new IllegalStateException("BUILDING_FIXTURE_TIMEOUT_"+stage);if(mc.player==null||mc.getSingleplayerServer()==null||busy)return;
            if(stage==0){nativeStep(p->{var server=p.level().getServer();server.getPlayerList().op(p.nameAndId());dev.mineagent.runtime.neoforge.WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("ArchitectTest",p.getUUID(),p.level(),p.position().add(-3,0,0)).agentId();origin=p.blockPosition().offset(8,15,8);source=design(4,4);var chest=origin.offset(2,1,2);p.level().setBlock(chest,Blocks.CHEST.defaultBlockState(),3);((net.minecraft.world.level.block.entity.ChestBlockEntity)p.level().getBlockEntity(chest)).setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));roofBaseline=net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(p.level().getBlockState(origin.offset(0,4,0)));return Map.of("fixtureAuthority",true,"origin",List.of(origin.getX(),origin.getY(),origin.getZ()));},1);return;}
            if(stage==1){call("plan_building",JSON.createObjectNode().put("revision",0).put("source",source.toString()),"PLANNED",2);return;}
            if(stage==2){call("apply_building",args(1),"UNVERIFIED",3);return;}
            if(stage==3){nativeStep(p->{require(((net.minecraft.world.level.block.entity.ChestBlockEntity)p.level().getBlockEntity(origin.offset(2,1,2))).getItem(0).getCount()==3,"HOLLOW_CLEARED_CHEST");require(p.level().getBlockState(origin).is(Blocks.STONE),"FLOOR_MISSING");require(p.level().getBlockState(origin.offset(2,1,0)).is(Blocks.OAK_DOOR)&&p.level().getBlockState(origin.offset(2,2,0)).is(Blocks.OAK_DOOR),"DOOR_HALVES_MISSING");return Map.of("interiorInventoryPreserved",true,"doorPair",true);},4);return;}
            if(stage==4){call("verify_building",args(1),"VERIFIED",5);return;}
            if(stage==5){source=design(6,4);call("plan_building",JSON.createObjectNode().put("revision",1).put("source",source.toString()),"PLANNED",6);return;}
            if(stage==6){call("apply_building",args(2),"UNVERIFIED",7);return;}
            if(stage==7){nativeStep(p->{require(p.level().getBlockState(origin).is(Blocks.STONE),"LOCAL_EDIT_CHANGED_FLOOR");require(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(p.level().getBlockState(origin.offset(0,4,0))).equals(roofBaseline),"REMOVAL_DID_NOT_RESTORE_BASELINE");p.level().setBlock(origin.offset(0,6,0),Blocks.GOLD_BLOCK.defaultBlockState(),3);return Map.of("localRoofMove",true,"otherPlayerConflictInserted",true);},8);return;}
            if(stage==8){call("control_building",args(2).put("action","undo"),"CONFLICT",9);return;}
            if(stage==9){nativeStep(p->{require(p.level().getBlockState(origin.offset(0,6,0)).is(Blocks.GOLD_BLOCK),"UNDO_OVERWROTE_PLAYER_CHANGE");p.level().setBlock(origin.offset(0,6,0),Blocks.OAK_PLANKS.defaultBlockState(),3);return Map.of("conflictPreserved",true);},10);return;}
            if(stage==10){call("control_building",args(2).put("action","undo"),"UNDONE",11);return;}
            if(stage==11){call("control_building",args(2).put("action","redo"),"UNVERIFIED",12);return;}
            if(stage==12){nativeStep(p->{p.level().setBlock(origin.offset(2,1,1),Blocks.STONE.defaultBlockState(),3);return Map.of("doorwayObstructedOutsideTargetFootprint",true);},13);return;}
            if(stage==13){call("verify_building",args(2),"UNVERIFIED",14);return;}
            if(stage==14){nativeStep(p->{p.level().setBlock(origin.offset(2,1,1),Blocks.AIR.defaultBlockState(),3);return Map.of("explicitFixtureRepair",true);},15);return;}
            if(stage==15){call("verify_building",args(2),"VERIFIED",16);return;}
            if(stage==16){source=design(6,30);call("plan_building",JSON.createObjectNode().put("revision",2).put("source",source.toString()),"PLANNED",17);return;}
            if(stage==17){nativeStep(p->{background=ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"apply_building",args(3).toString(),()->true);return Map.of("backgroundApplyStarted",true);},18);return;}
            if(stage==18){busy=true;server(p->dev.mineagent.runtime.neoforge.ui.ServerBuildings.inspect(p,agent,args(3))).whenComplete((value,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}if(Set.of("PREPARING","APPLYING").contains(value.get("status")))stage=19;else if(background.isDone())fail(new IllegalStateException("APPLY_FINISHED_BEFORE_PAUSE_FIXTURE"));}));return;}
            if(stage==19){call("control_building",args(3).put("action","pause"),"PAUSE_REQUESTED",20);return;}
            if(stage==20){if(!background.isDone())return;require(background.join().get("status").equals("PAUSED"),"PAUSE_NOT_ACKNOWLEDGED");receipts.add(background.join());stage=21;return;}
            if(stage==21){call("control_building",args(3).put("action","resume"),"UNVERIFIED",22);return;}
            if(stage==22){call("verify_building",args(3),"VERIFIED",23);return;}
            if(stage==23){Files.writeString(output().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","modelCalls",0,"receipts",receipts)));done=true;mc.stop();}
        }catch(Exception failure){fail(failure);}
    }
    private static CompletableFuture<Map<String,Object>> server(Function<ServerPlayer,CompletableFuture<Map<String,Object>>> action){var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();UUID owner=mc.player.getUUID();return server.submit(()->action.apply(server.getPlayerList().getPlayer(owner))).thenCompose(Function.identity());}
    private static void call(String tool,JsonNode args,String expected,int next){busy=true;server(p->ConversationAgentTools.execute(p,agent,UUID.randomUUID(),tool,args.toString(),()->true)).whenComplete((value,error)->Minecraft.getInstance().execute(()->{busy=false;if(error!=null){fail(error);return;}receipts.add(Map.of("tool",tool,"result",value));if(!expected.equals(value.get("status"))){fail(new IllegalStateException("EXPECTED_"+expected+"_GOT_"+value));return;}stage=next;}));}
    private static void nativeStep(Function<ServerPlayer,Map<String,Object>> action,int next){busy=true;server(p->CompletableFuture.completedFuture(action.apply(p))).whenComplete((value,error)->Minecraft.getInstance().execute(()->{busy=false;if(error!=null){fail(error);return;}receipts.add(value);stage=next;}));}
    private static JsonNode design(int height,int width){
        String template="""
            {"id":"home","name":"Component house","dimension":"minecraft:overworld","origin":ORIGIN,"components":[
            {"id":"floor","parts":[{"kind":"box","min":[0,0,0],"max":[4,0,4],"material":"minecraft:stone"},{"kind":"box","min":[2,0,-1],"max":[2,0,-1],"material":"minecraft:stone"}]},
            {"id":"walls","depends_on":["floor"],"parts":[{"kind":"box","min":[0,1,0],"max":[0,3,4],"material":"minecraft:bricks"},{"kind":"box","min":[4,1,0],"max":[4,3,4],"material":"minecraft:bricks"},{"kind":"box","min":[0,1,4],"max":[4,3,4],"material":"minecraft:bricks"},{"kind":"box","min":[1,1,0],"max":[1,3,0],"material":"minecraft:bricks"},{"kind":"box","min":[3,1,0],"max":[3,3,0],"material":"minecraft:bricks"},{"kind":"box","min":[2,3,0],"max":[2,3,0],"material":"minecraft:bricks"}]},
            {"id":"door","depends_on":["walls"],"parts":[{"kind":"box","min":[2,1,0],"max":[2,1,0],"material":"minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]"},{"kind":"box","min":[2,2,0],"max":[2,2,0],"material":"minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"}]},
            {"id":"roof","depends_on":["walls"],"parts":[{"kind":"box","min":[0,HEIGHT,0],"max":[WIDTH,HEIGHT,4],"material":"minecraft:oak_planks"}]}],
            "checks":[{"id":"entry_path","component":"floor","kind":"path","path":[[2,1,-1],[2,1,0],[2,1,1]],"headroom":2},{"id":"roof_size","component":"roof","kind":"bounds","min":[0,HEIGHT,0],"max":[WIDTH,HEIGHT,4]},{"id":"intentional_test_overhang","component":"roof","kind":"support","min":[0,HEIGHT,0],"max":[WIDTH,HEIGHT,4],"allow_floating":true}]}
            """;
        try{return JSON.readTree(template.replace("ORIGIN",List.of(origin.getX(),origin.getY(),origin.getZ()).toString()).replace("HEIGHT",Integer.toString(height)).replace("WIDTH",Integer.toString(width)));}catch(Exception error){throw new IllegalStateException(error);}
    }
    private static void fail(Throwable failure){if(done)return;done=true;try{Files.writeString(output().resolve("failure.json"),JSON.writeValueAsString(Map.of("stage",stage,"error",failure.toString(),"receipts",receipts)));}catch(Exception ignored){}Minecraft.getInstance().stop();}
    private ComponentBuildingSmokeClient(){}
}
