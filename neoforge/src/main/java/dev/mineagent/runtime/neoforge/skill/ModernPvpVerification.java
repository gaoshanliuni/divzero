package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.api.agent.BodyDomain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import net.minecraft.world.InteractionHand;
import java.util.*;
import java.nio.file.*;

/** Explicit disposable-world fixture. Does not run in ordinary worlds or alter production data. */
final class ModernPvpVerification {
    private static final UUID TOKEN=UUID.randomUUID();
    private static final Map<String,Object> RESULTS=new LinkedHashMap<>();
    private static int phase,started,placedBefore;private static Vec3 origin;private static float health;
    private static double straight;private static boolean done;
    static void tick(ServerPlayer viewer,MineAgentPlayer ai){
        if(done)return;
        if(!Boolean.getBoolean("mineagent.modernPvpFixture")||!Files.isRegularFile(Path.of("human-duel-instance.json")))return;
        int tick=ai.level().getServer().getTickCount();
        try{
            viewer.setInvulnerable(true);
            if(phase==0){
                SkillRuntime.cancelForBody(viewer,ai.agentId());
                require(ai.controls().acquire(TOKEN,EnumSet.allOf(BodyDomain.class),1000,()->!done,()->{}),"FIXTURE_LEASE");
                viewer.teleportTo(viewer.level(),12.5,101,787.5,Set.of(),0,0,true);
                PvpMapSupport.apply(ai,dev.mineagent.runtime.core.task.PvpMapProfile.defaults().ai());
                require(ai.getInventory().countItem(Items.GOLDEN_APPLE)==0&&ai.getOffhandItem().isEmpty(),"UNSELECTED_SUPPLY");
                var profile=dev.mineagent.runtime.core.task.PvpMapProfile.defaults().gear(0,"ai","secondary","minecraft:bow").gear(1,"ai","supply","minecraft:golden_apple").gear(2,"ai","offhand","minecraft:shield");
                PvpMapSupport.apply(ai,profile.ai());require(ai.getInventory().getItem(3).is(Items.BOW)&&ai.getInventory().getItem(2).getCount()==3&&ai.getOffhandItem().is(Items.SHIELD)&&ai.getInventory().countItem(Items.ARROW)==256,"SECONDARY_SUPPLY_OFFHAND");
                RESULTS.put("independentLoadout",true);System.setProperty("mineagent.disableSprint45","true");begin(ai,new Vec3(.5,101,790.5),new Vec3(.5,101,814.5));phase=1;started=tick;
            }else if(phase==1&&tick-started>=40){
                straight=ai.position().distanceTo(origin);RESULTS.put("straight40Ticks",straight);RESULTS.put("straightNavigation",ai.movementController().evidence());
                System.setProperty("mineagent.disableSprint45","false");begin(ai,new Vec3(.5,101,790.5),new Vec3(.5,101,814.5));phase=2;started=tick;
            }else if(phase==2&&tick-started>=40){
                double diagonal=ai.position().distanceTo(origin);RESULTS.put("diagonal40Ticks",diagonal);RESULTS.put("nativeDiagonalPercent",100*(diagonal/straight-1));RESULTS.put("diagonalInputTicks",ai.movementController().diagonalTicks());
                require(straight>5&&diagonal>5&&Math.abs(ai.getX()-.5)<.4,"NATIVE_SPRINT_DIRECTION");
                begin(ai,new Vec3(-15.5,101,789.5),new Vec3(-15.5,101,801.5));phase=3;started=tick;
            }else if(phase==3){
                if(ai.movementController().outcome().equals("ARRIVED")){
                    RESULTS.put("edgeArrivalTicks",tick-started);RESULTS.put("edgeNavigation",ai.movementController().evidence());
                    for(int y=101;y<=104;y++)ai.level().setBlock(new BlockPos(-12,y,794),Blocks.WHITE_WOOL.defaultBlockState(),3);
                    ai.getFoodData().setFoodLevel(10);health=ai.getHealth();RESULTS.put("predictedFourBlockDamage",NativeDropSafety.damage(ai,4));begin(ai,new Vec3(-11.5,105,794.5),new Vec3(-8.5,101,794.5));phase=4;started=tick;
                }else require(tick-started<150,"EDGE_ROUTE_TIMEOUT");
            }else if(phase==4){
                if(ai.onGround()&&ai.getY()<101.1&&ai.position().distanceTo(new Vec3(-8.5,101,794.5))<.5){
                    double damage=health-ai.getHealth();require(damage>0&&damage<=3,"NATIVE_AFFORDABLE_FALL");RESULTS.put("dropArrivalTicks",tick-started);RESULTS.put("actualFourBlockDamage",damage);RESULTS.put("dropNavigation",ai.movementController().evidence());
                    ai.movementController().stop();ai.getInventory().setItem(1,new ItemStack(Items.LIGHT_BLUE_WOOL,64));place(ai,new Vec3(4.5,101,795.5));origin=ai.position();phase=5;started=tick;
                }else require(tick-started<170,"DROP_ROUTE_TIMEOUT");
            }else if(phase==5){
                if(ai.getY()>=origin.y+2.95&&ai.onGround()){
                    int consumed=64-ai.getInventory().getItem(1).getCount();require(consumed==3,"NATIVE_COLUMN_CONSUMPTION");RESULTS.put("columnBlocksConsumed",consumed);RESULTS.put("columnTicks",tick-started);
                    ai.movementController().stop();place(ai,new Vec3(8.5,101,795.5));ai.getInventory().setItem(3,new ItemStack(Items.SHEARS));ai.getInventory().setSelectedSlot(0);
                    ai.level().setBlock(new BlockPos(9,101,795),Blocks.WHITE_WOOL.defaultBlockState(),3);ai.level().setBlock(new BlockPos(9,102,795),Blocks.WHITE_WOOL.defaultBlockState(),3);phase=6;started=tick;
                }else {if(!ai.movementController().recovering()&&ai.onGround())require(ai.movementController().recover(ai,origin.add(0,3,0)),"COLUMN_REQUEST");require(tick-started<160,"COLUMN_TIMEOUT");}
            }else if(phase==6){
                if(ai.level().getBlockState(new BlockPos(9,102,795)).isAir()){
                    require(ai.getMainHandItem().is(Items.SHEARS),"SHEARS_NATIVE_SELECTION");RESULTS.put("shearsBreakTicks",tick-started);RESULTS.put("shearsRecovery",ai.movementController().evidence());
                    ai.movementController().stop();ai.stopUsingItem();place(ai,new Vec3(8.5,101,807.5));ai.getInventory().setSelectedSlot(3);ai.getInventory().setItem(3,new ItemStack(Items.BOW));ai.setYRot(90);ai.setXRot(0);
                    placedBefore=ai.getInventory().countItem(Items.ARROW);require(ai.beginTaskItemUse(UUID.randomUUID(),InteractionHand.MAIN_HAND)&&ai.isUsingItem(),"NATIVE_BOW_START");phase=7;started=tick;
                }else {if(!ai.movementController().recovering())ai.movementController().recover(ai,new Vec3(11.5,101,795.5));require(tick-started<100,"SHEARS_TIMEOUT");}
            }else if(phase==7&&tick-started>=24){
                require(ai.isUsingItem()&&ai.getTicksUsingItem()>=20,"NATIVE_BOW_CHARGE");ai.releaseUsingItem();require(!ai.isUsingItem()&&ai.getInventory().countItem(Items.ARROW)==placedBefore-1,"NATIVE_BOW_RELEASE");RESULTS.put("bowChargeAndConsumption",true);
                ai.beginTaskItemUse(UUID.randomUUID(),InteractionHand.MAIN_HAND);ai.stopUsingItem();require(!ai.isUsingItem(),"NATIVE_BOW_CANCEL");
                verifyPlacement(viewer);RESULTS.put("nativePlacementAndProtection",true);
                RESULTS.put("health",ai.getHealth());RESULTS.put("movementAttribute",ai.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));finish(viewer,ai,"PASS","");
            }
        }catch(Throwable failure){finish(viewer,ai,"FAILED",failure.toString());}
    }
    private static void verifyPlacement(ServerPlayer p){
        p.stopUsingItem();p.teleportTo(p.level(),-4.5,101,800.5,Set.of(),-90,0,true);p.getInventory().setItem(1,new ItemStack(Items.WHITE_WOOL,64));p.getInventory().setSelectedSlot(1);NativeInventorySync.full(p);
        var floor=new BlockPos(-4,100,800);var hit=new BlockHitResult(new Vec3(-3.5,101,800.5),Direction.UP,floor,false);
        p.gameMode.useItemOn(p,p.level(),p.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
        require(p.level().getBlockState(floor.above()).is(Blocks.WHITE_WOOL)&&p.getInventory().getItem(1).getCount()==63,"PLACE_CONSUMPTION");
        require(!p.gameMode.destroyBlock(floor)&&p.level().getBlockState(floor).is(Blocks.BEDROCK)==false&&!p.level().getBlockState(floor).isAir(),"PERMANENT_FLOOR_PROTECTION");
        p.getInventory().setSelectedSlot(3);PvpMapSupport.apply(p,dev.mineagent.runtime.core.task.PvpMapProfile.defaults().human());require(p.getInventory().getSelectedSlot()==0&&!p.isUsingItem(),"EQUIP_SELECTED_SLOT_RESET");
    }
    private static void begin(MineAgentPlayer p,Vec3 from,Vec3 to){p.movementController().stop();place(p,from);origin=from;p.movementController().movePreciselyTo(to);p.setSprinting(true);}
    private static void place(MineAgentPlayer p,Vec3 at){p.teleportTo(p.level(),at.x,at.y,at.z,Set.of(),0,0,true);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.setOnGround(true);}
    private static void require(boolean pass,String message){if(!pass)throw new IllegalStateException(message);}
    private static void finish(ServerPlayer p,MineAgentPlayer ai,String status,String error){
        done=true;ai.movementController().stop();ai.stopUsingItem();ai.controls().release(TOKEN);RESULTS.put("source","FIXTURE_ONLY");RESULTS.put("status",status);RESULTS.put("error",error);RESULTS.put("phase",phase);
        try{Files.writeString(p.level().getServer().getServerDirectory().resolve("modern-pvp-fixture.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));}catch(Exception e){throw new IllegalStateException(e);}
        NativeHumanDuel.command(p,"stop");
    }
    private ModernPvpVerification(){}
}
