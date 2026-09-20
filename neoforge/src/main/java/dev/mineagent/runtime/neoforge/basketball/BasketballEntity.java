package dev.mineagent.runtime.neoforge.basketball;

import dev.mineagent.runtime.core.basketball.CourtPoint;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BasketballEntity extends ThrowableItemProjectile {
    private boolean registeredShot;
    private int lowSpeedTicks;

    public BasketballEntity(EntityType<? extends BasketballEntity> type, Level level) {
        super(type, level);
    }

    public BasketballEntity(Level level, LivingEntity owner, ItemStack stack) {
        super(MineAgentRegistries.BASKETBALL_ENTITY.get(), owner, level, stack);
    }

    public BasketballEntity(Level level, double x, double y, double z, ItemStack stack) {
        super(MineAgentRegistries.BASKETBALL_ENTITY.get(), x, y, z, level, stack);
    }

    @Override
    protected Item getDefaultItem() {
        return MineAgentRegistries.BASKETBALL.get();
    }

    @Override
    public void tick() {
        Vec3 previous = position();
        registerShotIfNeeded(previous);
        super.tick();
        if (!level().isClientSide() && isAlive()) {
            checkScores(previous, position());
            if (getDeltaMovement().lengthSqr() < 0.0025) {
                lowSpeedTicks++;
                if (lowSpeedTicks > 40) {
                    spawnAtLocation((ServerLevel) level(), getItem());
                    MineAgentRuntimeServices.basketball(level().getServer()).clearShot(getUUID());
                    discard();
                }
            } else {
                lowSpeedTicks = 0;
            }
        }
    }

    private void registerShotIfNeeded(Vec3 origin) {
        if (registeredShot || level().isClientSide()) {
            return;
        }
        if (getOwner() instanceof Player player) {
            MineAgentRuntimeServices.basketball(level().getServer()).beginShot(
                    getUUID(), player.getUUID(), point(origin));
            registeredShot = true;
        }
    }

    private void checkScores(Vec3 previous, Vec3 current) {
        BlockPos center = blockPosition();
        for (BlockPos position : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (!level().getBlockState(position).is(MineAgentRegistries.BASKETBALL_HOOP.get())) {
                continue;
            }
            CourtPoint rim = new CourtPoint(position.getX() + 0.5, position.getY() + 0.375, position.getZ() + 0.5);
            var result = MineAgentRuntimeServices.basketball(level().getServer())
                    .observe(getUUID(), point(previous), point(current), rim);
            if (result.scored()) {
                level().playSound(null, position, SoundEvents.NOTE_BLOCK_PLING.value(),
                        SoundSource.PLAYERS, 1.0F, result.points() == 3 ? 1.5F : 1.2F);
                MineAgentNetwork.sendBasketballScore(level().getServer(), result.playerId(), result.points(), result.totalScore());
                return;
            }
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        Vec3 movement = getDeltaMovement();
        Vec3 bounced = switch (hit.getDirection().getAxis()) {
            case X -> new Vec3(-movement.x * 0.68, movement.y * 0.82, movement.z * 0.82);
            case Y -> new Vec3(movement.x * 0.82, -movement.y * 0.68, movement.z * 0.82);
            case Z -> new Vec3(movement.x * 0.82, movement.y * 0.82, -movement.z * 0.68);
        };
        setDeltaMovement(bounced);
        setPos(position().add(hit.getDirection().getUnitVec3().scale(0.02)));
    }

    private static CourtPoint point(Vec3 position) {
        return new CourtPoint(position.x, position.y, position.z);
    }
}
