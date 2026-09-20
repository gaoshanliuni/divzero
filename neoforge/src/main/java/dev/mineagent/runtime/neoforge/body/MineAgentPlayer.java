package dev.mineagent.runtime.neoforge.body;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;

public final class MineAgentPlayer extends ServerPlayer {
    private final UUID agentId;
    private final UUID ownerPlayerId;
    private Component agentDisplayName;
    private final MineAgentMovementController movementController = new MineAgentMovementController();
    private final Consumer<MineAgentPlayer> deathCallback;
    private final dev.mineagent.runtime.core.agent.AgentBodyLifecycle lifecycle=new dev.mineagent.runtime.core.agent.AgentBodyLifecycle();
    private final dev.mineagent.runtime.core.agent.AgentBodyLifecycle playerTicks=new dev.mineagent.runtime.core.agent.AgentBodyLifecycle();
    public long nativeTicks(){return playerTicks.ticks();}
    public boolean deathAccepted(){return lifecycle.deathAccepted();}
    public boolean canAct(){return isAlive()&&!isRemoved()&&!deathAccepted()&&!lifecycle.endReturnAccepted()&&!lifecycle.stopping()&&!isSpectator();}
    private boolean loadedNativeState,persistedEndReturn;
    public boolean endReturnAccepted(){return lifecycle.endReturnAccepted();}
    public boolean loadedNativeState(){return loadedNativeState;}
    private BlockPos miningTarget;
    private int miningStartedTick;
    private int actionSequence;
    private final dev.mineagent.runtime.core.task.ActionControlLease taskControl=new dev.mineagent.runtime.core.task.ActionControlLease();
    private UUID miningOperation;
    private net.minecraft.world.level.block.state.BlockState miningExpected;
    private MiningReceipt miningReceipt;
    private long itemUseRevision,ownedUseRevision;
    private UUID itemUseOperation;private boolean startingTaskUse,nativeUseFinished;
    private InteractionHand taskUseHand;
    public boolean ownsItemUse(UUID operation){return operation!=null&&operation.equals(itemUseOperation)&&ownedUseRevision==itemUseRevision;}
    public boolean taskItemUseCurrent(UUID operation){return ownsItemUse(operation)&&(!isUsingItem()||getUsedItemHand()==taskUseHand&&getUseItem()==getItemInHand(taskUseHand));}
    public boolean itemUseFinished(UUID operation){return ownsItemUse(operation)&&nativeUseFinished;}
    public void abortItemUseIfCurrent(UUID operation){if(ownsItemUse(operation)&&isUsingItem())stopUsingItem();}
    public boolean beginTaskItemUse(UUID operation,InteractionHand hand){
        if(!canAct()||isUsingItem()||getItemInHand(hand).isEmpty())return false;
        itemUseOperation=java.util.Objects.requireNonNull(operation);taskUseHand=hand;nativeUseFinished=false;ownedUseRevision=itemUseRevision;startingTaskUse=true;
        try{var result=gameMode.useItem(this,level(),getItemInHand(hand),hand);return result.consumesAction()||isUsingItem();}
        finally{startingTaskUse=false;}
    }
    @Override public void startUsingItem(InteractionHand hand){if(!canAct())return;boolean alreadyUsing=isUsingItem();super.startUsingItem(hand);if(!alreadyUsing&&isUsingItem()){itemUseRevision++;if(startingTaskUse)ownedUseRevision=itemUseRevision;}}
    @Override protected void completeUsingItem(){
        if(!canAct()||!validateTaskControl()||!canAct()){stopUsingItem();return;}
        UUID operation=itemUseOperation;boolean tracked=ownsItemUse(operation)&&isUsingItem()&&getUsedItemHand()==taskUseHand&&getUseItem()==getItemInHand(taskUseHand);
        super.completeUsingItem();
        if(tracked&&ownsItemUse(operation)&&!isUsingItem())nativeUseFinished=true;
    }
    public record MiningReceipt(UUID operation,String state,String before,String after,int toolDamageBefore,int toolDamageAfter,boolean removed,int tick){}
    public boolean claimTaskControl(UUID token,java.util.function.BooleanSupplier guard,Runnable cancel){return canAct()&&taskControl.claim(token,guard,cancel);}
    public void releaseTaskControl(UUID token){taskControl.release(token);}
    public boolean taskControlOwned(){return taskControl.owned();}
    public boolean validateTaskControl(){return taskControl.validate();}
    public boolean validateMiningState(BlockPos pos){
        if(miningTarget!=null&&miningTarget.equals(pos)&&!level().getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4)){finishMining("TARGET_UNLOADED",false,null);return false;}
        if(miningTarget!=null&&miningTarget.equals(pos)&&level().getBlockState(pos)!=miningExpected){finishMining("TARGET_CHANGED",false,null);return false;}return true;
    }
    public UUID miningOperation(){return miningOperation;}
    public java.util.Optional<MiningReceipt> miningReceipt(UUID token){return miningReceipt!=null&&miningReceipt.operation().equals(token)?java.util.Optional.of(miningReceipt):java.util.Optional.empty();}
    public void abortMiningIfCurrent(UUID token){if(token!=null&&token.equals(miningOperation))abortMining();}

    public MineAgentPlayer(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            UUID agentId,
            UUID ownerPlayerId,
            String displayName,
            Consumer<MineAgentPlayer> deathCallback
    ) {
        this(server,level,profile,agentId,ownerPlayerId,displayName,deathCallback,ClientInformation.createDefault());
    }
    MineAgentPlayer(MinecraftServer server,ServerLevel level,GameProfile profile,UUID agentId,UUID ownerPlayerId,String displayName,Consumer<MineAgentPlayer> deathCallback,ClientInformation info){
        super(server,level,profile,info);
        this.agentId = agentId;
        this.ownerPlayerId = ownerPlayerId;
        this.agentDisplayName = Component.literal(displayName);
        this.deathCallback = deathCallback;
    }

    public MineAgentPlayer replacementForRespawn(MinecraftServer server,ServerLevel level,GameProfile profile,ClientInformation info){
        if(!profile.id().equals(agentId)||server!=level().getServer())throw new IllegalArgumentException("AGENT_RESPAWN_IDENTITY");
        return new MineAgentPlayer(server,level,profile,agentId,ownerPlayerId,agentDisplayName.getString(),deathCallback,info);
    }

    public UUID agentId() {
        return agentId;
    }

    public UUID ownerPlayerId() {
        return ownerPlayerId;
    }

    public MineAgentMovementController movementController() {
        return movementController;
    }

    public void setAgentDisplayName(String displayName) {
        this.agentDisplayName = Component.literal(displayName);
        setCustomName(this.agentDisplayName);
    }

    public boolean beginMining(BlockPos target) {
        return beginMining(target,UUID.randomUUID());
    }
    public boolean beginMining(BlockPos target,UUID operation){
        if (!canAct() || target == null || !isWithinBlockInteractionRange(target, 1.0)
                || level().getBlockState(target).isAir()) {
            return false;
        }
        if (miningTarget != null) {
            abortMining();
        }
        miningTarget = target.immutable();
        miningOperation=java.util.Objects.requireNonNull(operation);miningExpected=level().getBlockState(target);miningReceipt=null;
        miningStartedTick = level().getServer().getTickCount();
        gameMode.handleBlockBreakAction(
                miningTarget,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP,
                level().getMaxY(),
                ++actionSequence
        );
        if(miningTarget!=null&&gameMode instanceof dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess access&&!NativeMiningState.tracking(access,miningTarget))finishMining("NATIVE_START_REJECTED",false,null);
        return true;
    }

    public java.util.Optional<BlockPos> miningTarget() {
        return java.util.Optional.ofNullable(miningTarget);
    }

    public void abortMining() {
        if (miningTarget != null) {
            if(level().getChunkSource().hasChunk(miningTarget.getX()>>4,miningTarget.getZ()>>4))gameMode.handleBlockBreakAction(
                    miningTarget,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP,
                    level().getMaxY(),
                    ++actionSequence
            );
            if(gameMode instanceof dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess access)NativeMiningState.cancel(access,miningTarget);
            finishMining("ABORTED",false,null);
            miningTarget = null;
        }
    }

    @Override
    public Component getDisplayName() {
        return agentDisplayName;
    }

    @Override
    public boolean isFakePlayer() {
        return true;
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public void tick() {
        if(isRemoved()||!lifecycle.enterTick(level().getServer().getTickCount()))return;
        taskControl.validate();
        if(miningTarget!=null)validateMiningState(miningTarget);
        if (level().getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            level().getChunkSource().move(this);
        }
        super.tick();
        doTick();
        if(isAlive()&&!lifecycle.deathAccepted()){movementController.tick(this);tickMining();}
    }

    @Override public void doTick(){if(isRemoved()||!playerTicks.enterTick(level().getServer().getTickCount()))return;super.doTick();}

    @Override public ServerPlayer teleport(net.minecraft.world.level.portal.TeleportTransition transition){
        ServerPlayer placed=super.teleport(transition);
        // Native placement/tracking is finished. There is no remote AI client to acknowledge it;
        // leaving this vanilla flag set would make this body permanently invulnerable after travel.
        // This does not acknowledge any third-party client handshake or a cancelled transition.
        if(placed==this&&!isRemoved()&&isChangingDimension())hasChangedDimension();
        return placed;
    }

    @SuppressWarnings("deprecation") // Target 26.1.2 still persists playerGameType using LEGACY_ID_CODEC.
    @Override protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input){
        super.readAdditionalSaveData(input);loadedNativeState=true;
        persistedEndReturn=input.getBooleanOr("mineagent_end_return_pending",false);
        // NeoForge's generic fake-player default discards the saved game type even during load.
        // This complete body must retain the vanilla hardcore death result before it becomes visible.
        if(level().getServer().isHardcore()&&input.read("playerGameType",net.minecraft.world.level.GameType.LEGACY_ID_CODEC)
                .filter(mode->mode==net.minecraft.world.level.GameType.SPECTATOR).isPresent()){
            ((dev.mineagent.runtime.neoforge.mixin.AgentGameModeAccess)gameMode).mineagent$restoreGameMode(net.minecraft.world.level.GameType.SPECTATOR,
                    input.read("previousPlayerGameType",net.minecraft.world.level.GameType.LEGACY_ID_CODEC).orElse(null));
        }
    }
    @Override protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output){
        super.addAdditionalSaveData(output);
        output.putBoolean("mineagent_end_return_pending",persistedEndReturn||lifecycle.endReturnAccepted());
    }

    @Override public void showEndCredits(){
        if(isRemoved()||deathAccepted()||endReturnAccepted()||wonGame)return;
        super.showEndCredits();
        if(wonGame&&isRemoved()&&lifecycle.acceptEndReturn()){
            stopBodyControlForReturn();deathCallback.accept(this);
        }
    }

    /** A ServerPlayer's move skips fall accounting: the real listener normally performs it.
     * This server-owned body has no movement packets, so account only actual collision-clipped moves. */
    @Override public void move(net.minecraft.world.entity.MoverType type,net.minecraft.world.phys.Vec3 requested){
        var before=position();super.move(type,requested);
        if(isRemoved())return;
        var moved=position().subtract(before);
        if(!isLocalInstanceAuthoritative())doCheckFallDamage(moved.x,moved.y,moved.z,onGround());
        if(moved.y>0)resetFallDistance();
        if(onGround()||hasLandedInLiquid()||onClimbable()||isSpectator()||isFallFlying()||isAutoSpinAttack())tryResetCurrentImpulseContext();
        checkMovementStatistics(moved.x,moved.y,moved.z);
    }

    public void ensureTicked(int serverTick) {
        if(!isRemoved())tick();
    }

    private void tickMining() {
        if (miningTarget == null) {
            return;
        }
        var state = level().getBlockState(miningTarget);
        if(!isAlive()||state!=miningExpected||!validateTaskControl()){
            if(miningTarget!=null){if(gameMode instanceof dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess access)NativeMiningState.cancel(access,miningTarget);finishMining("TARGET_CHANGED",false,null);}
            return;
        }
        int elapsed = level().getServer().getTickCount() - miningStartedTick + 1;
        float progress = state.getDestroyProgress(this, level(), miningTarget) * elapsed;
        if (progress >= 0.7F) {
            gameMode.handleBlockBreakAction(
                    miningTarget,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP,
                    level().getMaxY(),
                    ++actionSequence
            );
        } else if (elapsed > 1_200 || !isWithinBlockInteractionRange(miningTarget, 1.0)) {
            abortMining();
        } else if ((elapsed & 3) == 0) {
            swing(InteractionHand.MAIN_HAND, true);
        }
    }
    public void nativeMiningResult(BlockPos pos,net.minecraft.world.level.block.state.BlockState before,net.minecraft.world.item.ItemStack tool,boolean removed){
        if(miningTarget!=null&&miningTarget.equals(pos)&&miningExpected==before)finishMining(removed?"BROKEN":"NATIVE_REMOVE_REJECTED",removed,tool);
    }
    public void nativeMiningRejected(BlockPos pos){if(miningTarget!=null&&miningTarget.equals(pos))finishMining("NATIVE_BREAK_REJECTED",false,null);}
    private void finishMining(String state,boolean removed,net.minecraft.world.item.ItemStack beforeTool){
        if(miningTarget==null)return;
        boolean loaded=level().getChunkSource().hasChunk(miningTarget.getX()>>4,miningTarget.getZ()>>4);
        String after=NativeMiningState.afterState(loaded,()->net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level().getBlockState(miningTarget)));
        miningReceipt=new MiningReceipt(miningOperation,state,net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(miningExpected),after,beforeTool==null?-1:beforeTool.getDamageValue(),getMainHandItem().getDamageValue(),removed,level().getServer().getTickCount());
        if(gameMode instanceof dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess access)NativeMiningState.cancel(access,miningTarget);
        if(loaded)level().destroyBlockProgress(getId(),miningTarget,-1);miningTarget=null;
    }

    @Override
    public void die(DamageSource source) {
        if(lifecycle.deathAccepted())return;
        super.die(source);
    }
    public void nativeDeathAccepted(){
        if(getHealth()>0||!lifecycle.acceptDeath())return;
        stopBodyControlForReturn();deathCallback.accept(this);
    }
    private void stopBodyControlForReturn(){
        itemUseOperation=null;nativeUseFinished=false;
        // A task/plugin cancellation failure must not skip the remaining native cleanup or respawn.
        for(Runnable cleanup:new Runnable[]{()->{if(isUsingItem())stopUsingItem();},taskControl::cancel,this::abortMining,movementController::stop}){
            try{cleanup.run();}catch(RuntimeException error){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("AI body death cleanup failed for {}",agentId,error);}
        }
    }
    /** Native Player.remove(KILLED) returns/drops cursor and crafting inputs before restoreFrom.
     * Closing sooner would return inputs to an already-dropped inventory and then lose them. */
    @Override public void doCloseContainer(){if(lifecycle.deferContainerClose(isRemoved()))return;super.doCloseContainer();}
    /** Restore a known pending native return, never replay a death/loot event. */
    public void restoreDeadLogin(){
        if(getHealth()<=0&&lifecycle.acceptDeath()){deathCallback.accept(this);return;}
        if(persistedEndReturn&&seenCredits&&level().dimension()==net.minecraft.world.level.Level.END&&lifecycle.acceptEndReturn()){
            wonGame=true;deathCallback.accept(this);
        }
    }
    /** Called before vanilla saves players/world chunks, never from ServerStoppedEvent. */
    public void prepareForServerStop(){
        if(!lifecycle.beginStop())return;
        playerTicks.beginStop();stopBodyControlForReturn();
        if(!isRemoved()&&(deathAccepted()||getHealth()<=0)){
            // Native removal drops still-owned cursor/crafting inputs; saving first would lose them.
            level().removePlayerImmediately(this,net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        }else if(!isRemoved()){
            boolean separate=containerMenu!=inventoryMenu;doCloseContainer();if(separate)inventoryMenu.removed(this);
        }
    }
}
