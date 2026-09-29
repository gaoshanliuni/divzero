package dev.mineagent.runtime.neoforge.client.body;

import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;import dev.mineagent.runtime.neoforge.WorldActivationRuntime;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;import dev.mineagent.runtime.neoforge.ui.ConversationAgentTools;
import net.minecraft.client.Minecraft;import net.minecraft.core.*;import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;import net.minecraft.world.item.*;import net.minecraft.world.level.GameType;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;import net.neoforged.bus.api.SubscribeEvent;import net.neoforged.fml.common.EventBusSubscriber;import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;

/** Isolated world acceptance through the real public tool path; never enabled in a normal installation. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PersistentSkillSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();private record Step(String name,int timeout,Supplier<CompletableFuture<Boolean>> run){}
    private static final Deque<Step> STEPS=new ArrayDeque<>();private static final List<Object> EVIDENCE=new ArrayList<>();
    private static UUID agent,second,zombie;private static int ticks,stageAt;private static boolean initialized,busy,done;private static Vec3 stoppedAt;private static long baseline;
    private static JsonNode lastObservation;
    private static final List<UUID> manyAgents=new ArrayList<>();
    private static boolean playerActor(){return System.getProperty("mineagent.skillSmokeActor","ai").equals("player");}
    private static ServerPlayer controlled(ServerPlayer p){return playerActor()?p:body(p);}
    private static Minecraft mc(){return Minecraft.getInstance();}private static Path root()throws Exception{return Files.createDirectories(mc().gameDirectory.toPath().resolve(Boolean.getBoolean("mineagent.skillSmokeResume")?"persistent-skill-resume":"persistent-skill-smoke"));}
    private static Path identityFile(){return mc().gameDirectory.toPath().resolve("persistent-skill-smoke/identity.json");}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> work){var out=new CompletableFuture<T>();var s=mc().getSingleplayerServer();UUID player=mc().player.getUUID();s.submit(()->work.apply(s.getPlayerList().getPlayer(player))).whenComplete((value,error)->mc().execute(()->{if(error!=null)out.completeExceptionally(error);else out.complete(value);}));return out;}
    private static MineAgentPlayer body(ServerPlayer p){return MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow();}
    private static CompletableFuture<JsonNode> tool(UUID ai,String name,ObjectNode args){return server(p->ConversationAgentTools.execute(p,ai,UUID.randomUUID(),name,args.toString(),()->true)).thenCompose(Function.identity()).thenApply(value->{var n=JSON.valueToTree(value);if(n.path("status").asText().equals("REJECTED"))throw new IllegalStateException(name+":"+n);return n;});}
    private static CompletableFuture<JsonNode> tool(String name,ObjectNode args){return tool(agent,name,args);}
    private static JsonNode session(JsonNode result,String id){for(var value:result.path("skills"))if(value.path("session").path("spec").path("id").asText().equals(id))return value.path("session");throw new IllegalStateException("SKILL_NOT_LISTED_"+id);}
    private static CompletableFuture<Boolean> state(String id,Predicate<JsonNode> ready){return tool("inspect_skills",JSON.createObjectNode()).thenApply(value->{lastObservation=value;var s=session(value,id);if(Set.of("FAILED","PAUSED").contains(s.path("state").asText()))throw new IllegalStateException("SKILL_RUNTIME_"+s);if(ready.test(s)){EVIDENCE.add(value);return true;}if(Set.of("COMPLETED","CANCELLED").contains(s.path("state").asText()))throw new IllegalStateException("SKILL_ENDED_WITHOUT_EXPECTED_PROOF_"+s);return false;});}
    private static void action(String name,Supplier<CompletableFuture<?>> action){STEPS.add(new Step(name,800,()->action.get().thenApply(value->{if(value!=null)EVIDENCE.add(Map.of("step",name,"result",value));return true;})));}
    private static void waitFor(String name,int timeout,Supplier<CompletableFuture<Boolean>> predicate){STEPS.add(new Step(name,timeout,predicate));}
    private static ObjectNode start(String id,String actor){return JSON.createObjectNode().put("id",id).put("actor",playerActor()?"player":actor).put("dimension","minecraft:overworld").put("expected_revision",0).put("defend",false);}
    private static ObjectNode area(String id,String actor,int x1,int y1,int z1,int x2,int y2,int z2){var n=start(id,actor);n.putArray("min").add(x1).add(y1).add(z1);n.putArray("max").add(x2).add(y2).add(z2);return n;}
    private static CompletableFuture<JsonNode> stop(String id){return tool("control_skill",JSON.createObjectNode().put("id",id).put("expected_revision",1).put("action","stop"));}
    private static void screen(String name){try{net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name+".png"));}catch(Exception failure){fail(failure);}});}catch(Exception e){fail(e);}}
    private static void prepare(){
        if(Boolean.getBoolean("mineagent.skillSmokeResume")){restoreLifecycle();return;}
        action("world-and-real-survival-bodies",()->server(p->{
            var s=p.level().getServer();s.getPlayerList().op(p.nameAndId());p.setGameMode(GameType.CREATIVE);WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);p.teleportTo(p.level(),.5,101,-3.5,Set.of(),0,0,true);
            for(int x=-12;x<=31;x++)for(int z=-7;z<=26;z++)for(int y=98;y<=109;y++)p.level().setBlock(new BlockPos(x,y,z),y<=100?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("持续技能搭档",p.getUUID(),p.level(),new Vec3(.5,101,6.5)).agentId();var b=body(p);b.setGameMode(GameType.SURVIVAL);b.getInventory().clearContent();b.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));b.getInventory().setItem(1,new ItemStack(Items.WHEAT_SEEDS,32));b.getInventory().setItem(2,new ItemStack(Items.IRON_HOE));b.getInventory().setItem(3,new ItemStack(Items.BOW));b.getInventory().setItem(4,new ItemStack(Items.ARROW,32));var rod=new ItemStack(Items.FISHING_ROD);var lure=p.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.LURE);net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(rod,e->e.set(lure,3));b.getInventory().setItem(5,rod);return Map.of("agent",agent,"mode",b.gameMode.getGameModeForPlayer().name());
        }));
        String mode=System.getProperty("mineagent.skillSmokeMode","work");
        if(playerActor()){action("real-player-equipment-and-input",()->{org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());return server(p->{var b=body(p);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();for(int i=0;i<36;i++)p.getInventory().setItem(i,b.getInventory().getItem(i).copy());p.inventoryMenu.broadcastChanges();b.teleportTo(p.level(),27.5,101,25.5,Set.of(),0,0,true);return null;});});}
        if(mode.equals("navigation")){navigation();return;}if(mode.equals("fishing")){fishing();return;}if(mode.equals("combat")){combat();return;}if(mode.equals("player")){player();return;}if(mode.equals("cooperation")){cooperation();return;}if(mode.equals("patrol")){patrol();return;}if(mode.equals("performance")){performance();return;}if(mode.equals("lifecycle")){lifecycle();return;}if(mode.equals("uncertain")){uncertain();return;}work();
    }
    private static void navigation(){
        action("forced-door-slab-crouch-water-ladder-route",()->server(p->{var b=body(p);for(int x=-1;x<=23;x++)for(int y=101;y<=107;y++){p.level().setBlock(new BlockPos(x,y,-1),Blocks.STONE.defaultBlockState(),2);p.level().setBlock(new BlockPos(x,y,1),Blocks.STONE.defaultBlockState(),2);}for(int y=101;y<=107;y++){p.level().setBlock(new BlockPos(-1,y,0),Blocks.STONE.defaultBlockState(),2);p.level().setBlock(new BlockPos(23,y,0),Blocks.STONE.defaultBlockState(),2);}
            var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.WEST);p.level().setBlock(new BlockPos(4,101,0),door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),2);p.level().setBlock(new BlockPos(4,102,0),door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),2);p.level().setBlock(new BlockPos(5,102,0),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP),2);p.level().setBlock(new BlockPos(7,101,0),Blocks.STONE_SLAB.defaultBlockState(),2);
            for(int x=9;x<=12;x++){p.level().setBlock(new BlockPos(x,99,0),Blocks.WATER.defaultBlockState(),2);p.level().setBlock(new BlockPos(x,100,0),Blocks.WATER.defaultBlockState(),2);}for(int x=17;x<=22;x++)for(int y=101;y<=103;y++)p.level().setBlock(new BlockPos(x,y,0),Blocks.STONE.defaultBlockState(),2);for(int y=101;y<=103;y++)p.level().setBlock(new BlockPos(16,y,0),Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING,Direction.WEST),2);
            controlled(p).teleportTo(p.level(),.5,101,.5,Set.of(),-90,0,true);if(!playerActor())b.movementController().movePreciselyTo(new Vec3(21.5,104,.5));return null;}));
        if(playerActor()){
            action("real-player-terrain-patrol",()->{var n=start("terrain","player").put("repeat",false);n.putArray("route").addArray().add(21.5).add(104).add(.5);return tool("patrol_route",n);});
            waitFor("real-player-terrain-arrival",2200,()->state("terrain",s->s.path("state").asText().equals("COMPLETED")));
            action("real-player-terrain-motion-proof",()->{var proof=NativeSkillInput.motionEvidence();require(((Number)proof.get("climbingTicks")).intValue()>0&&((Number)proof.get("swimmingTicks")).intValue()>0&&((Number)proof.get("crouchingTicks")).intValue()>0,"REAL_PLAYER_TERRAIN_ACTIONS_MISSING "+proof);EVIDENCE.add(proof);return CompletableFuture.completedFuture(null);});return;
        }
        waitFor("native-route-arrival",1800,()->server(p->{var b=body(p);var nav=b.movementController();if(!nav.outcome().equals("ARRIVED")){require(nav.outcome().equals("MOVING"),"NAVIGATION_"+nav.outcome()+" "+nav.evidence()+" "+b.position());return false;}var proof=nav.evidence();require(((Number)proof.get("openedDoors")).intValue()>0&&((Number)proof.get("crouchingSteps")).intValue()>0&&((Number)proof.get("swimmingSteps")).intValue()>0&&((Number)proof.get("climbingSteps")).intValue()>0,"NAVIGATION_ACTIONS_MISSING "+proof);EVIDENCE.add(proof);return true;}));
        action("sealed-unreachable-test",()->server(p->{var b=body(p);for(int x=-7;x<=-5;x++)for(int z=4;z<=6;z++)if(x!=-6||z!=5)for(int y=101;y<=105;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),2);b.teleportTo(p.level(),-5.5,101,5.5,Set.of(),0,0,true);baseline=p.level().getServer().getTickCount();b.movementController().moveTo(new Vec3(-1.5,101,5.5));return null;}));
        waitFor("retry-backoff-is-real",300,()->server(p->{var b=body(p);if(!b.movementController().outcome().equals("UNREACHABLE"))return false;long elapsed=p.level().getServer().getTickCount()-baseline;require(elapsed>=60,"EMPTY_ROUTE_BYPASSED_BACKOFF");EVIDENCE.add(Map.of("retryElapsed",elapsed,"search",b.movementController().evidence()));return true;}));
    }
    private static void crops(ServerPlayer p,int from,int to,int z){for(int x=from;x<=to;x++){p.level().setBlock(new BlockPos(x,100,z),Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE,7),2);p.level().setBlock(new BlockPos(x,101,z),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);}p.level().setBlock(new BlockPos(from,100,z+1),Blocks.WATER.defaultBlockState(),2);}
    private static void work(){
        action("farm-targets",()->server(p->{crops(p,3,5,6);return null;}));action("start-farm-through-tool",()->tool("farm_area",area("farm","ai",3,101,6,5,101,6).put("defend",true).put("crop","minecraft:wheat")));
        waitFor("real-harvest-and-replant",1400,()->state("farm",s->s.path("counters").path("planted").asInt()>=1));
        action("interrupt-farm-with-monster",()->server(p->{var b=body(p);var mob=EntityType.ZOMBIE.create(p.level(),EntitySpawnReason.COMMAND);mob.setPos(b.getX()+2,101,b.getZ());mob.setNoAi(true);mob.setHealth(4);mob.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));p.level().addFreshEntity(mob);zombie=mob.getUUID();return Map.of("zombie",zombie);}));
        waitFor("native-defense-damage",1000,()->state("farm",s->s.path("counters").path("verifiedHits").asInt()>0));
        waitFor("monster-defeated",1000,()->server(p->{var e=p.level().getEntity(zombie);return e==null||!e.isAlive();}));
        action("mature-after-defense",()->server(p->{crops(p,3,5,6);return null;}));
        action("save-resume-counter",()->tool("inspect_skills",JSON.createObjectNode()).thenAccept(v->baseline=session(v,"farm").path("counters").path("planted").asLong()));
        waitFor("farm-resumed-after-defense",1400,()->state("farm",s->s.path("counters").path("planted").asLong()>baseline));
        action("switch-to-follow",()->tool("follow_entity",start("follow","ai").put("target","$owner")));
        action("owner-moves-first",()->server(p->{p.teleportTo(p.level(),12.5,101,8.5,Set.of(),0,0,true);return null;}));
        waitFor("follow-started",500,()->server(p->body(p).getX()>5));
        action("owner-moves-again",()->server(p->{p.teleportTo(p.level(),22.5,101,11.5,Set.of(),0,0,true);return null;}));
        waitFor("follows-live-entity-position",1400,()->server(p->body(p).distanceTo(p)<3));
        action("cancel-follow",()->stop("follow"));action("remember-stop-position",()->server(p->{stoppedAt=body(p).position();baseline=p.level().getServer().getTickCount();return null;}));
        waitFor("cancel-is-terminal",100,()->server(p->{if(p.level().getServer().getTickCount()-baseline<40)return false;require(body(p).position().distanceToSqr(stoppedAt)<.1,"CANCELLED_SKILL_MOVED");return true;}));
    }
    private static void fishing(){
        action("native-fishing-water-and-shore",()->server(p->{for(int x=-6;x<=-1;x++)for(int z=12;z<=18;z++)for(int y=99;y<=100;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.WATER.defaultBlockState(),2);controlled(p).teleportTo(p.level(),.5,101,14.5,Set.of(),90,25,true);return null;}));
        action("start-fishing",()->tool("fish_at",area("fish","ai",-6,100,12,-1,100,18).put("limit",1)));
        waitFor("actual-bite-reel-loot",7500,()->state("fish",s->s.path("state").asText().equals("COMPLETED")&&s.path("counters").path("fishingCatches").asInt()==1));
    }
    private static void combat(){
        action("ranged-target",()->server(p->{controlled(p).teleportTo(p.level(),.5,101,6.5,Set.of(),-90,0,true);var mob=EntityType.ZOMBIE.create(p.level(),EntitySpawnReason.COMMAND);mob.setPos(12.5,101,6.5);mob.setNoAi(true);mob.setHealth(4);mob.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));p.level().addFreshEntity(mob);zombie=mob.getUUID();return null;}));
        action("start-native-bow-combat",()->tool("combat_entity",start("bow","ai").put("target",zombie.toString())));
        waitFor("native-bow-hit-and-consumption",1800,()->state("bow",s->s.path("state").asText().equals("COMPLETED")&&s.path("counters").path("verifiedHits").asInt()>0&&s.path("counters").path("arrowsReleased").asInt()>0));
    }
    private static void cooperation(){
        action("two-survival-farmers-one-plot",()->server(p->{var manager=MineAgentRuntimeServices.bodies(p.level().getServer());second=manager.createPersistentAt("协作农务搭档",p.getUUID(),p.level(),new Vec3(.5,101,10.5)).agentId();var b=manager.body(second).orElseThrow();b.setGameMode(GameType.SURVIVAL);b.getInventory().clearContent();b.getInventory().setItem(0,new ItemStack(Items.WHEAT_SEEDS,16));body(p).teleportTo(p.level(),.5,101,8.5,Set.of(),0,0,true);crops(p,5,5,9);return null;}));
        action("simultaneous-farm-intents",()->CompletableFuture.allOf(tool("farm_area",area("coop_a","ai",5,101,9,5,101,9).put("crop","minecraft:wheat")),tool(second,"farm_area",area("coop_b","ai",5,101,9,5,101,9).put("crop","minecraft:wheat"))));
        waitFor("one-harvest-one-replant",1500,()->tool("inspect_skills",JSON.createObjectNode()).thenCombine(tool(second,"inspect_skills",JSON.createObjectNode()),(a,b)->{var sa=session(a,"coop_a");var sb=session(b,"coop_b");long harvested=sa.path("counters").path("harvested").asLong()+sb.path("counters").path("harvested").asLong(),planted=sa.path("counters").path("planted").asLong()+sb.path("counters").path("planted").asLong();require(harvested<=1&&planted<=1,"DUPLICATED_SHARED_PLOT");if(planted==1){require(harvested==1,"PLANT_WITHOUT_NATIVE_HARVEST");EVIDENCE.add(Map.of("first",a,"second",b));return true;}return false;}));
        action("stop-both-farmers",()->CompletableFuture.allOf(stop("coop_a"),tool(second,"control_skill",JSON.createObjectNode().put("id","coop_b").put("expected_revision",1).put("action","stop"))));
        action("start-paused-farm",()->tool("farm_area",area("paused_farm","ai",5,101,9,5,101,9).put("crop","minecraft:wheat")));
        waitFor("no-mature-crop-is-waiting",300,()->state("paused_farm",s->s.path("state").asText().equals("WAITING")));
        action("explicit-pause",()->tool("control_skill",JSON.createObjectNode().put("id","paused_farm").put("expected_revision",1).put("action","pause")));
        action("mature-paused-plot",()->server(p->{crops(p,5,5,9);baseline=p.level().getServer().getTickCount();return null;}));
        waitFor("pause-keeps-crop-untouched",150,()->server(p->{if(p.level().getServer().getTickCount()-baseline<40)return false;require(p.level().getBlockState(new BlockPos(5,101,9)).getValue(CropBlock.AGE)==7,"PAUSED_FARM_CHANGED_WORLD");return true;}));
        action("resume-same-session",()->tool("control_skill",JSON.createObjectNode().put("id","paused_farm").put("expected_revision",2).put("action","resume")));
        waitFor("resumed-native-result",1000,()->state("paused_farm",s->s.path("counters").path("planted").asLong()>=1));
        action("stop-resumed-session",()->tool("control_skill",JSON.createObjectNode().put("id","paused_farm").put("expected_revision",3).put("action","stop")));
    }
    private static void patrol(){
        action("start-patrol-route",()->{var n=start("patrol","ai").put("repeat",false).put("dwell_ticks",10);var r=n.putArray("route");r.addArray().add(6.5).add(101).add(6.5);r.addArray().add(6.5).add(101).add(11.5);r.addArray().add(.5).add(101).add(11.5);return tool("patrol_route",n);});
        waitFor("all-waypoints-reached",1600,()->state("patrol",s->s.path("state").asText().equals("COMPLETED")&&s.path("counters").path("waypointsReached").asInt()==3));
        action("start-reachable-idle",()->tool("wander_area",area("wander","ai",-3,100,4,9,102,15).put("limit",2).put("dwell_ticks",5)));
        waitFor("reachable-idle-visits",1600,()->state("wander",s->s.path("state").asText().equals("COMPLETED")&&s.path("counters").path("wanderVisits").asInt()==2));
    }
    private static void performance(){
        action("sixteen-isolated-navigation-intents",()->server(p->{var manager=MineAgentRuntimeServices.bodies(p.level().getServer());for(int i=0;i<16;i++){int x=-8+(i%4)*3,z=3+(i/4)*4;var id=manager.createPersistentAt("并发寻路"+i,p.getUUID(),p.level(),new Vec3(x+.5,101,z+.5)).agentId();manyAgents.add(id);var b=manager.body(id).orElseThrow();b.movementController().movePreciselyTo(new Vec3(x+14.5,101,z+.5));}baseline=p.level().getServer().getTickCount();return null;}));
        waitFor("all-searches-get-budget-and-move",1600,()->server(p->{var manager=MineAgentRuntimeServices.bodies(p.level().getServer());int moved=0;var values=new ArrayList<Object>();for(var id:manyAgents){var b=manager.body(id).orElseThrow();if(b.movementController().executedSteps()>0)moved++;values.add(Map.of("id",id,"position",b.position().toString(),"navigation",b.movementController().evidence()));}if(moved<16)return false;var budget=dev.mineagent.runtime.neoforge.body.NativeNavigationBudget.get(p.level().getServer()).observation();require(((Number)budget.get("maxReservedNodesPerTick")).intValue()<=1024,"SEARCH_BUDGET_EXCEEDED");EVIDENCE.add(Map.of("agents",16,"elapsedTicks",p.level().getServer().getTickCount()-baseline,"budget",budget,"navigation",values));return true;}));
    }
    private static void lifecycle(){
        action("restart-farm-plot",()->server(p->{crops(p,3,3,6);return null;}));
        action("start-restartable-intent",()->tool("farm_area",area("saved_farm","ai",3,101,6,3,101,6).put("crop","minecraft:wheat")));
        waitFor("durable-progress-before-restart",1200,()->state("saved_farm",s->s.path("counters").path("planted").asInt()>=1));
        action("pause-before-world-close",()->tool("control_skill",JSON.createObjectNode().put("id","saved_farm").put("expected_revision",1).put("action","pause")));
        action("persist-restart-identity",()->server(p->{try{crops(p,3,3,6);Files.writeString(root().resolve("identity.json"),JSON.writeValueAsString(Map.of("agent",agent,"seeds",body(p).getInventory().countItem(Items.WHEAT_SEEDS),"sourcePhase","PREPARED_FOR_REAL_RESTART")));return null;}catch(Exception e){throw new CompletionException(e);}}));
    }
    private static void restoreLifecycle(){
        action("read-same-world-identity",()->{try{agent=UUID.fromString(JSON.readTree(Files.readString(identityFile())).path("agent").asText());return CompletableFuture.completedFuture(null);}catch(Exception e){return CompletableFuture.failedFuture(e);}});
        waitFor("same-ai-restored",600,()->server(p->MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).isPresent()));
        action("observe-saved-session",()->tool("inspect_skills",JSON.createObjectNode()).thenAccept(value->{var s=session(value,"saved_farm");require(s.path("state").asText().equals("PAUSED")&&s.path("revision").asInt()==2&&s.path("counters").path("planted").asInt()==1,"RESTART_DID_NOT_PRESERVE_PAUSED_PROGRESS");EVIDENCE.add(value);}));
        action("restart-observation-start",()->server(p->{baseline=p.level().getServer().getTickCount();return null;}));
        waitFor("restart-does-not-replay",350,()->server(p->{if(p.level().getServer().getTickCount()-baseline<160)return false;try{var original=JSON.readTree(Files.readString(identityFile()));require(body(p).getInventory().countItem(Items.WHEAT_SEEDS)==original.path("seeds").asInt(),"RESTART_CONSUMED_INVENTORY");require(p.level().getBlockState(new BlockPos(3,101,6)).getValue(CropBlock.AGE)==7,"RESTART_CHANGED_PAUSED_CROP");return true;}catch(Exception e){throw new CompletionException(e);}}));
        action("explicit-resume-saved-session",()->tool("control_skill",JSON.createObjectNode().put("id","saved_farm").put("expected_revision",2).put("action","resume")));
        waitFor("saved-session-continues",1200,()->state("saved_farm",s->s.path("counters").path("planted").asInt()==2));
        action("cancel-resumed-session",()->tool("control_skill",JSON.createObjectNode().put("id","saved_farm").put("expected_revision",3).put("action","stop")));
    }
    private static void uncertain(){
        action("uncertain-effect-plot",()->server(p->{crops(p,3,3,6);return null;}));
        action("one-cycle-with-receipt-fault",()->tool("farm_area",area("uncertain_farm","ai",3,101,6,3,101,6).put("repeat",false).put("crop","minecraft:wheat")));
        waitFor("native-effect-retained-with-uncertain-receipt",1200,()->tool("inspect_skills",JSON.createObjectNode()).thenApply(value->{lastObservation=value;var s=session(value,"uncertain_farm");if(!s.path("state").asText().equals("PAUSED"))return false;require(s.path("receipt").path("state").asText().equals("PREPARED")&&s.path("reason").asText().contains("ACCEPTANCE_AFTER_NATIVE_PLANT"),"FAULT_NOT_AFTER_REAL_PLANT");EVIDENCE.add(value);return true;}));
        action("record-actual-effect",()->server(p->{require(p.level().getBlockState(new BlockPos(3,101,6)).is(Blocks.WHEAT),"UNKNOWN_WORLD_EFFECT_MISSING");baseline=body(p).getInventory().countItem(Items.WHEAT_SEEDS);return Map.of("seedCountAfterNativePlant",baseline);}));
        action("reject-blind-resume",()->server(p->ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"control_skill",JSON.createObjectNode().put("id","uncertain_farm").put("expected_revision",1).put("action","resume").toString(),()->true)).thenCompose(Function.identity()).handle((value,error)->{require(error!=null||!"APPLIED".equals(value.get("status")),"UNCERTAIN_RESUMED_WITHOUT_RECONCILE");return Map.of("blindResumeRejected",true);}));
        action("observe-instead-of-replay",()->tool("control_skill",JSON.createObjectNode().put("id","uncertain_farm").put("expected_revision",1).put("action","reconcile")));
        action("new-explicit-resume-after-observation",()->tool("control_skill",JSON.createObjectNode().put("id","uncertain_farm").put("expected_revision",2).put("action","resume")));
        waitFor("reconciled-scan-complete",500,()->state("uncertain_farm",s->s.path("state").asText().equals("COMPLETED")));
        action("no-second-consumption",()->server(p->{require(body(p).getInventory().countItem(Items.WHEAT_SEEDS)==baseline,"UNCERTAIN_ACTION_REPLAYED");return null;}));
    }
    private static void player(){
        action("player-farm-setup",()->server(p->{body(p).teleportTo(p.level(),5.5,101,16.5,Set.of(),0,0,true);p.setGameMode(GameType.SURVIVAL);p.teleportTo(p.level(),.5,101,6.5,Set.of(),-90,0,true);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));p.getInventory().setItem(12,new ItemStack(Items.WHEAT_SEEDS,12));p.inventoryMenu.broadcastChanges();crops(p,3,3,6);return null;}));
        action("focus-only-test-game",()->{org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());return CompletableFuture.completedFuture(null);});
        action("start-real-player-farm",()->tool("farm_area",area("player_farm","player",3,101,6,3,101,6).put("crop","minecraft:wheat")));
        waitFor("native-player-input-harvest-replant",1800,()->state("player_farm",s->s.path("counters").path("planted").asInt()>=1));
        action("released-cursor-and-append-button",()->{require(!mc().mouseHandler.isMouseGrabbed(),"AUTONOMY_CURSOR_NOT_RELEASED");dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.smokeClick("append");require(mc().screen instanceof net.minecraft.client.gui.screens.ChatScreen,"APPEND_DID_NOT_OPEN_CHAT");return CompletableFuture.completedFuture(null);});
        action("grow-while-chat-open",()->server(p->{crops(p,3,3,6);return null;}));
        waitFor("chat-does-not-pause-farming",1000,()->state("player_farm",s->s.path("counters").path("planted").asInt()>=2));
        action("open-native-f2-while-farming",()->{require(mc().screen instanceof net.minecraft.client.gui.screens.ChatScreen,"CHAT_WAS_CLOSED_BY_CONTROL");dev.mineagent.runtime.neoforge.client.nativeui.NativeWorkspaceScreen.open();return server(p->{crops(p,3,3,6);return null;});});
        waitFor("f2-does-not-pause-farming",1000,()->state("player_farm",s->s.path("counters").path("planted").asInt()>=3));
        action("background-test-game",()->{mc().setScreen(null);org.lwjgl.glfw.GLFW.glfwIconifyWindow(mc().getWindow().handle());return server(p->{crops(p,3,3,6);return null;});});
        waitFor("background-does-not-pause-farming",1400,()->state("player_farm",s->s.path("counters").path("planted").asInt()>=4));
        action("restore-test-game",()->{org.lwjgl.glfw.GLFW.glfwRestoreWindow(mc().getWindow().handle());org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());return CompletableFuture.completedFuture(null);});
        waitFor("panel-ready-after-background",150,()->CompletableFuture.completedFuture(mc().isWindowActive()&&Boolean.TRUE.equals(dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.observation().get("visible"))));
        action("pause-using-hud-button",()->{dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.smokeClick("pause");return CompletableFuture.completedFuture(null);});
        waitFor("manual-pause-acknowledged",150,()->CompletableFuture.completedFuture(AutonomousBodyClient.manuallyPaused()));
        action("grow-during-manual-pause",()->server(p->{crops(p,3,3,6);baseline=p.level().getServer().getTickCount();return null;}));
        waitFor("manual-pause-blocks-actions",350,()->server(p->{if(p.level().getServer().getTickCount()-baseline<140)return false;require(p.level().getBlockState(new BlockPos(3,101,6)).getValue(CropBlock.AGE)==7,"MANUAL_PAUSE_STILL_HARVESTED");return true;}));
        action("continue-using-hud-button",()->{dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.smokeClick("pause");return CompletableFuture.completedFuture(null);});
        waitFor("manual-resume-continues-work",1000,()->state("player_farm",s->s.path("counters").path("planted").asInt()>=5));
        action("exit-using-hud-button",()->{screen("real-player-controls");dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.smokeClick("exit");require(!AutonomousBodyClient.active(),"EXIT_BUTTON_FAILED");return CompletableFuture.completedFuture(null);});
        waitFor("exit-button-cancels-skill",150,()->state("player_farm",s->s.path("state").asText().equals("CANCELLED")));
        action("restart-to-check-escape",()->tool("farm_area",area("player_esc","player",3,101,6,3,101,6).put("crop","minecraft:wheat")));
        waitFor("escape-session-active",150,()->CompletableFuture.completedFuture(AutonomousBodyClient.active()&&!mc().mouseHandler.isMouseGrabbed()));
        action("real-player-escape",()->{screen("real-player-farm");require(AutonomousBodyClient.active(),"PLAYER_ADAPTER_NOT_ACTIVE");PlayerBodyControlClient.physicalKey(mc().getWindow().handle(),1,new net.minecraft.client.input.KeyEvent(256,0,0));require(!AutonomousBodyClient.active(),"ESC_DID_NOT_RELEASE");return CompletableFuture.completedFuture(null);});
        waitFor("escape-cancels-skill",150,()->state("player_esc",s->s.path("state").asText().equals("CANCELLED")));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!Boolean.getBoolean("mineagent.skillSmoke")||done)return;try{
        if(mc().player==null||mc().getSingleplayerServer()==null)return;ticks++;if(!initialized){initialized=true;prepare();stageAt=ticks;}if(ticks%100==0)Files.writeString(root().resolve("progress.json"),JSON.writeValueAsString(Map.of("stage",STEPS.isEmpty()?"DONE":STEPS.getFirst().name,"ticks",ticks,"busy",busy,"observed",lastObservation==null?Map.of():lastObservation,"input",NativeSkillInput.observation())));
        if(STEPS.isEmpty()){Files.writeString(root().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","mode",System.getProperty("mineagent.skillSmokeMode","work"),"modelCalls",0,"evidence",EVIDENCE)));done=true;mc().stop();return;}
        if(ticks-stageAt>STEPS.getFirst().timeout)throw new IllegalStateException("SKILL_SMOKE_TIMEOUT_"+STEPS.getFirst().name);if(busy||ticks%5!=0)return;busy=true;var step=STEPS.getFirst();step.run.get().whenComplete((finished,error)->mc().execute(()->{busy=false;if(error!=null){fail(error);return;}if(finished){EVIDENCE.add(Map.of("completedStep",step.name,"atTick",ticks));STEPS.removeFirst();stageAt=ticks;}}));
    }catch(Exception e){fail(e);}}
    private static void fail(Throwable failure){if(done)return;done=true;try{screen("failure");Files.writeString(root().resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure.toString(),"stage",STEPS.isEmpty()?"DONE":STEPS.getFirst().name,"evidence",EVIDENCE,"lastObservation",lastObservation==null?Map.of():lastObservation,"input",NativeSkillInput.observation(),"autonomy",AutonomousBodyClient.observe())));}catch(Exception ignored){}AutonomousBodyClient.stop("SMOKE_FAILURE");mc().stop();}
    private PersistentSkillSmokeClient(){}
}
