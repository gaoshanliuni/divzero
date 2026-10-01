package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/** Permanent lobby and tightly scoped, wool-only round cleanup for the marked map. */
public final class PvpMapArena {
    public static boolean field(BlockPos p){return p.getX()>=-16&&p.getX()<=16&&p.getZ()>=784&&p.getZ()<=816&&p.getY()>=101&&p.getY()<=319;}
    public static boolean inside(Vec3 p){return p.x> -17&&p.x<18&&p.z>783&&p.z<818&&p.y>=97;}
    public static boolean protectedArea(BlockPos p){return p.getX()>=-18&&p.getX()<=18&&p.getZ()>=754&&p.getZ()<=818&&p.getY()>=97&&p.getY()<=319;}
    public static int clearWool(ServerPlayer player){
        var level=player.level();int count=0;for(int x=-16;x<=16;x++)for(int z=784;z<=816;z++)for(int y=101;y<=level.getMaxY();y++){var at=new BlockPos(x,y,z);if(level.getBlockState(at).is(net.minecraft.tags.BlockTags.WOOL)){level.setBlock(at,Blocks.AIR.defaultBlockState(),3);count++;}}
        for(var drop:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(-17,100,783,18,125,818),e->e.getItem().is(net.minecraft.tags.ItemTags.WOOL)))drop.discard();return count;
    }
    public static void lobby(ServerPlayer p){
        var level=p.level();for(int x=-11;x<=11;x++)for(int z=754;z<=782;z++)level.getChunk(new BlockPos(x,100,z));
        for(int x=-10;x<=10;x++)for(int z=755;z<=781;z++)for(int y=99;y<=109;y++){
            boolean rim=Math.abs(x)==10||z==755||z==781;var block=y==99?Blocks.BEDROCK:y==100?rim?Blocks.POLISHED_DEEPSLATE:Math.abs(x)<=2?Blocks.SMOOTH_QUARTZ:Blocks.SMOOTH_STONE:Blocks.AIR;
            if(y==101&&rim&&!(Math.abs(x)<=2&&z==781))block=Blocks.GLASS;
            if(Math.abs(x)==8&&(z==758||z==777)&&y>=101&&y<=106)block=Blocks.DARK_OAK_LOG;
            if(y==106&&Math.abs(x)<=9&&z>=757&&z<=778)block=Math.abs(x)==9||z==757||z==778?Blocks.POLISHED_DEEPSLATE:Math.abs(x)<=3?Blocks.GLASS:Blocks.SMOOTH_QUARTZ;
            if(y==105&&Math.abs(x)==7&&(z==759||z==776))block=Blocks.SEA_LANTERN;
            if(y==100&&Math.abs(x)<=3&&z>=763&&z<=769)block=Math.abs(x)==3||z==763||z==769?Blocks.CYAN_TERRACOTTA:Blocks.SMOOTH_QUARTZ;
            var at=new BlockPos(x,y,z);if(!level.getBlockState(at).is(block))level.setBlock(at,block.defaultBlockState(),2);
        }
        for(int x=-2;x<=2;x++)for(int z=782;z<=783;z++)level.setBlock(new BlockPos(x,100,z),Blocks.SMOOTH_QUARTZ.defaultBlockState(),2);
        var source=p.createCommandSourceStack().withSuppressedOutput();p.level().getServer().getCommands().performPrefixedCommand(source,"setworldspawn 0 101 766 0");p.level().getServer().getCommands().performPrefixedCommand(source,"spawnpoint @s 0 101 766 0");
    }
    public static void returnToLobby(ServerPlayer p){if(!p.isAlive())return;p.teleportTo(p.level(),.5,101,766.5,Set.of(),0,0,true);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.setInvulnerable(true);}
    private PvpMapArena(){}
}
