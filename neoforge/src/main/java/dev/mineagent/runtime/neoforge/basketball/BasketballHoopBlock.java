package dev.mineagent.runtime.neoforge.basketball;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BasketballHoopBlock extends Block {
    public static final MapCodec<BasketballHoopBlock> CODEC = simpleCodec(BasketballHoopBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
            box(0, 6, 14, 16, 16, 16),
            box(7, 0, 14, 9, 6, 16),
            box(2, 5, 2, 14, 6, 3),
            box(2, 5, 13, 14, 6, 14),
            box(2, 5, 3, 3, 6, 13),
            box(13, 5, 3, 14, 6, 13)
    );

    public BasketballHoopBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            CollisionContext context
    ) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            CollisionContext context
    ) {
        return SHAPE;
    }
}
