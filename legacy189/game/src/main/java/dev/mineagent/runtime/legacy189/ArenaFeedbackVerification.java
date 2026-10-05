package dev.mineagent.runtime.legacy189;

import com.google.gson.JsonObject;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import java.util.*;

/** Real native fixtures for the five map feedback items; never run outside explicit fixture mode. */
public final class ArenaFeedbackVerification {
    private final JsonObject evidence;
    private final List<Entity> debris=new ArrayList<Entity>();
    private final Map<BlockPos,IBlockState> scene=new LinkedHashMap<BlockPos,IBlockState>();
    private final EntityItem outside;
    private int stage, began;
    private double farX, farZ;
    public ArenaFeedbackVerification(EntityPlayerMP human,JsonObject evidence){
        this.evidence=evidence;
        debris.add(new EntityItem(human.worldObj,12.5,102,808.5,new ItemStack(Items.diamond_sword)));
        debris.add(new EntityItem(human.worldObj,3.5,102,766.5,new ItemStack(Items.golden_apple)));
        debris.add(new EntityXPOrb(human.worldObj,12.5,102,808.5,4));
        debris.add(new EntityArrow(human.worldObj,12.5,102,808.5));
        for(Entity item:debris)human.worldObj.spawnEntityInWorld(item);
        human.worldObj.setBlockState(new BlockPos(25,100,800),Blocks.stone.getDefaultState());
        outside=new EntityItem(human.worldObj,25.5,101,800.5,new ItemStack(Items.iron_sword));outside.setPickupDelay(32767);human.worldObj.spawnEntityInWorld(outside);
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private void put(EntityPlayerMP player,int x,int y,int z,net.minecraft.block.Block block){
        BlockPos pos=new BlockPos(x,y,z);if(!scene.containsKey(pos)){require(player.worldObj.isAirBlock(pos),"FEEDBACK_SCENE_NOT_EMPTY "+pos);scene.put(pos,player.worldObj.getBlockState(pos));}
        player.worldObj.setBlockState(pos,block.getDefaultState(),3);
    }
    private void clear(EntityPlayerMP player){for(Map.Entry<BlockPos,IBlockState> entry:scene.entrySet())player.worldObj.setBlockState(entry.getKey(),entry.getValue(),3);scene.clear();}
    private static int apples(EntityPlayerMP player){int amount=0;for(ItemStack stack:player.inventory.mainInventory)if(stack!=null&&stack.getItem()==Items.golden_apple)amount+=stack.stackSize;ItemStack hand=NativeOffhand.get(player).stack();if(hand!=null&&hand.getItem()==Items.golden_apple)amount+=hand.stackSize;return amount;}
    public void tick(NativeDuel.Session run,int tick){
        EntityPlayerMP human=run.human;
        if(stage>0&&stage<7&&tick-began>1100)throw new IllegalStateException("FEEDBACK_TIMEOUT stage="+stage+" phase="+run.phase+" nav="+run.navigation.reason+" ai="+(run.ai==null?"none":run.ai.getPositionVector()));
        if(stage==0&&run.phase.equals("COUNTDOWN")){
            require(apples(human)==16&&human.inventory.mainInventory[2].getItem()==Items.golden_apple,"SELECTED_GOLDEN_APPLES_HOTBAR");
            require(apples(run.ai)==0,"UNSELECTED_AI_GOLDEN_APPLES");
            for(Entity item:debris)require(item.isDead,"NON_WOOL_DEBRIS_NOT_CLEARED");require(!outside.isDead,"OUTSIDE_ARENA_ITEM_REMOVED");
            evidence.addProperty("selectedGoldenApples",16);evidence.addProperty("unselectedGoldenApples",0);evidence.addProperty("allArenaDebrisCleared",true);evidence.addProperty("outsideItemPreserved",true);
            began=tick;stage=1;
        }else if(stage==1&&run.phase.equals("FIGHTING")){
            require(NativeFixture.packedFoodUsed&&apples(human)==15&&human.getAbsorptionAmount()>0,"NATIVE_GOLDEN_APPLE_USE");
            evidence.addProperty("nativeGoldenAppleConsumed",true);
            noCooldown(run);
            for(int y=101;y<=103;y++){
                for(int z=798;z<=802;z++)put(human,3,y,z,Blocks.iron_block);
                for(int x=4;x<=8;x++){put(human,x,y,798,Blocks.iron_block);put(human,x,y,802,Blocks.iron_block);}
            }
            farX=run.ai.posX;farZ=0;began=tick;stage=2;
        }else if(stage==2&&run.ai!=null){
            farX=Math.max(farX,run.ai.posX);farZ=Math.max(farZ,Math.abs(run.ai.posZ-800.5));
            if(run.meleeHits>0){
                require(farX>8.8&&farZ>2.6&&run.navigation.plans>0,"NATIVE_U_WALL_NOT_ROUTED");
                for(BlockPos pos:scene.keySet())require(human.worldObj.getBlockState(pos).getBlock()==Blocks.iron_block,"PERMANENT_WALL_BROKEN");
                evidence.addProperty("nativeUWallDetour",true);evidence.addProperty("detourMaxX",farX);evidence.addProperty("detourMaxZ",farZ);clear(human);stage=3;began=tick;
            }
        }else if(stage==3&&run.rounds==1&&run.phase.equals("READY")){
            require(human.worldObj.getEntitiesWithinAABB(EntityItem.class,new AxisAlignedBB(-19,0,753,20,256,820)).isEmpty(),"DEATH_DROPS_REMAIN");
            evidence.addProperty("deathDropsCleared",true);stage=4;began=tick;
        }else if(stage==4&&run.phase.equals("COUNTDOWN")){
            require(apples(human)==16&&apples(run.ai)==0,"NEXT_ROUND_SUPPLY_SELECTION");
            require(human.getAbsorptionAmount()==0&&human.getActivePotionEffects().isEmpty(),"OLD_APPLE_EFFECTS_REMAIN");
            cage(human,Blocks.wool,3);stage=5;began=tick;
        }else if(stage==5&&run.ai!=null&&run.phase.equals("FIGHTING")&&Math.hypot(run.ai.posX-5.5,run.ai.posZ-800.5)>3){
            require(run.navigation.woolBroken>0&&run.navigation.placed()==0,"WOOL_ESCAPE_NOT_NATIVE");
            evidence.addProperty("nativeWoolEscape",true);evidence.addProperty("escapeWoolBroken",run.navigation.woolBroken);clear(human);
            run.ai.playerNetServerHandler.setPlayerLocation(5.5,101,800.5,90,0);run.ai.motionX=run.ai.motionY=run.ai.motionZ=0;run.ai.fallDistance=0;
            run.navigation.reset(run.ai);run.ai.inventory.mainInventory[1]=new ItemStack(Blocks.wool,64,3);
            cage(human,Blocks.iron_block,2);
            // Upstream recovery requires two onward transitions within 1.25 blocks.
            // Four isolated column tops only offer two-block drops and are rejected.
            // Join those tops into an existing ledge, as in the upstream pit fixture.
            for(int y=101;y<=102;y++)for(int x:new int[]{4,6})for(int z:new int[]{799,801})put(human,x,y,z,Blocks.iron_block);
            stage=6;began=tick;
        }else if(stage==6&&run.ai!=null&&Math.hypot(run.ai.posX-5.5,run.ai.posZ-800.5)>3){
            require(run.navigation.placed()>0&&run.navigation.consumed()>0&&run.navigation.woolBroken==0,"NATIVE_SUPPORT_ESCAPE_FAILED");
            require(run.ai.inventory.mainInventory[1]!=null&&run.ai.inventory.mainInventory[1].stackSize==64-run.navigation.consumed(),"ESCAPE_MATERIAL_NOT_CONSUMED");
            for(BlockPos pos:scene.keySet())require(human.worldObj.getBlockState(pos).getBlock()==Blocks.iron_block,"ESCAPE_BROKE_MAP");
            evidence.addProperty("nativeJumpPlaceEscape",true);evidence.addProperty("escapeWoolPlaced",run.navigation.placed());evidence.addProperty("escapeMaterialsConsumed",run.navigation.consumed());
            clear(human);NativeFixture.packedStopAllowed=true;stage=7;
        }
    }
    private void cage(EntityPlayerMP player,net.minecraft.block.Block block,int height){for(int y=101;y<101+height;y++)for(int[] d:new int[][]{{4,800},{6,800},{5,799},{5,801}})put(player,d[0],y,d[1],block);}
    private void noCooldown(NativeDuel.Session run){
        EntityPlayerMP human=run.human;NativeAgent ai=run.ai;ItemStack[] armor=human.inventory.armorInventory.clone();
        Arrays.fill(human.inventory.armorInventory,null);human.clearActivePotions();human.setAbsorptionAmount(0);human.setHealth(20);human.hurtResistantTime=0;
        ai.playerNetServerHandler.setPlayerLocation(-2.5,101,800.5,90,0);ai.setSprinting(false);ai.fallDistance=0;
        float before=human.getHealth();ai.attackTargetEntityWithCurrentItem(human);float first=before-human.getHealth();
        before=human.getHealth();ai.attackTargetEntityWithCurrentItem(human);float immune=before-human.getHealth();
        // Only the fixture clears victim immunity to isolate attacker charge damage.
        human.hurtResistantTime=0;before=human.getHealth();ai.attackTargetEntityWithCurrentItem(human);float repeat=before-human.getHealth();
        require(Math.abs(first-7)<.002&&Math.abs(repeat-first)<.002&&immune==0,"ATTACK_COOLDOWN_OR_IMMUNITY_CHANGED "+first+"/"+repeat+"/"+immune);
        evidence.addProperty("firstSwordDamage",first);evidence.addProperty("immediateRepeatSwordDamage",repeat);evidence.addProperty("nativeHurtImmunityPreserved",true);
        System.arraycopy(armor,0,human.inventory.armorInventory,0,4);human.setHealth(20);human.hurtResistantTime=0;human.motionX=human.motionY=human.motionZ=0;
        ai.playerNetServerHandler.setPlayerLocation(5.5,101,800.5,90,0);ai.motionX=ai.motionY=ai.motionZ=0;run.navigation.reset(ai);
    }
}
