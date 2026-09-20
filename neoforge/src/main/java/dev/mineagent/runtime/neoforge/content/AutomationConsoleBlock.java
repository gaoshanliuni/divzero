package dev.mineagent.runtime.neoforge.content;

import com.mojang.serialization.MapCodec;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class AutomationConsoleBlock extends Block {
    public static final MapCodec<AutomationConsoleBlock> CODEC = simpleCodec(AutomationConsoleBlock::new);

    public AutomationConsoleBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
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
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    serverPlayer, new MineAgentPayloads.OpenPanel("CREATOR"));
        }
        return InteractionResult.SUCCESS;
    }
}
