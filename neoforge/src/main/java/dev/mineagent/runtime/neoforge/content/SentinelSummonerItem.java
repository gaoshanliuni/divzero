package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class SentinelSummonerItem extends Item {
    public SentinelSummonerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel() instanceof ServerLevel level) {
            SentinelBoss boss = MineAgentRegistries.SENTINEL_BOSS.get()
                    .create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
            if (boss == null) {
                return InteractionResult.FAIL;
            }
            Vec3 position = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
            boss.snapTo(position.x, position.y, position.z, context.getRotation(), 0);
            boss.setHealth(boss.getMaxHealth());
            if (!level.addFreshEntity(boss)) {
                return InteractionResult.FAIL;
            }
            if (context.getPlayer() == null || !context.getPlayer().hasInfiniteMaterials()) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
