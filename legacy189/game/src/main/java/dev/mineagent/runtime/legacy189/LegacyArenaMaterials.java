package dev.mineagent.runtime.legacy189;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/** Only player-built temporary terrain and legacy arena wool are editable. */
public final class LegacyArenaMaterials {
    private LegacyArenaMaterials(){}
    public static boolean editable(World world,BlockPos pos){
        if(world.isRemote||world.provider.getDimensionId()!=0||!NativeArena.ready()||!NativeArena.field(pos)||!world.isBlockLoaded(pos)||world.getTileEntity(pos)!=null)return false;
        Block block=world.getBlockState(pos).getBlock();
        return block!=Blocks.air&&block.getBlockHardness(world,pos)>=0&&(block==Blocks.wool||NativeRuntime.data().temporary(pos));
    }
    public static void clear(World world,BlockPos pos){
        if(editable(world,pos))world.setBlockToAir(pos);
        NativeRuntime.data().temporary(pos,false);
    }
}
