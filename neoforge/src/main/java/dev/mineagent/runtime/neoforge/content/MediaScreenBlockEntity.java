package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class MediaScreenBlockEntity extends BlockEntity {
    public MediaScreenBlockEntity(BlockPos position, BlockState state) {
        super(MineAgentRegistries.MEDIA_SCREEN_BLOCK_ENTITY.get(), position, state);
    }
}
