package dev.mineagent.runtime.neoforge.content;

import com.mojang.serialization.MapCodec;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class MediaScreenBlock extends BaseEntityBlock {

    public MediaScreenBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

@Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new MediaScreenBlockEntity(position, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            BlockHitResult hit
    ) {
        if (player instanceof ServerPlayer serverPlayer) {
            dev.mineagent.runtime.neoforge.network.MineAgentNetwork.openMediaPanel(serverPlayer, position);
        }
        return InteractionResult.SUCCESS;
    }
}
