package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.skill.WaterClutchRuntime;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

/** Observe successful native bucket use; do not alter its result, inventory or world changes. */
@Mixin(BucketItem.class)
public abstract class WaterBucketReceiptMixin {
    @Unique private static final ThreadLocal<Deque<Optional<WaterClutchRuntime.PlacementObservation>>> divzero$bucketCalls=ThreadLocal.withInitial(ArrayDeque::new);
    @Inject(method="use",at=@At("HEAD"))
    private void divzero$bucketBefore(Level level,Player player,InteractionHand hand,CallbackInfoReturnable<InteractionResult> callback){
        var observed=player instanceof ServerPlayer p&&((BucketItem)(Object)this).getContent()==net.minecraft.world.level.material.Fluids.WATER?WaterClutchRuntime.beforePlacement(p):null;
        divzero$bucketCalls.get().push(Optional.ofNullable(observed));
    }
    @Inject(method="use",at=@At("RETURN"))
    private void divzero$bucketAfter(Level level,Player player,InteractionHand hand,CallbackInfoReturnable<InteractionResult> callback){
        var calls=divzero$bucketCalls.get();if(calls.isEmpty())return;var observed=calls.pop();if(calls.isEmpty())divzero$bucketCalls.remove();
        if(callback.getReturnValue().consumesAction())observed.ifPresent(WaterClutchRuntime::placed);
    }
}
