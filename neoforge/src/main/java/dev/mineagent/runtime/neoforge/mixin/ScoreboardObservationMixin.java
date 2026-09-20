package dev.mineagent.runtime.neoforge.mixin;

import dev.mineagent.runtime.neoforge.scoreboard.ScoreboardReadWindow;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded holder names and one-way objective replacement invalidation. No change to scores or mutation results. */
@Mixin(Scoreboard.class)
public abstract class ScoreboardObservationMixin implements ScoreboardReadWindow {
    @Shadow @Final private Map<String,?> playerScores;
    @Shadow public abstract Objective getObjective(String name);
    @Unique private final Map<String,AtomicLong> mineagent$objectiveEpochs=new ConcurrentHashMap<>();
    @Override public List<String> mineagent$scoreHolderNames(int maximum){if(maximum<1||maximum>4096||playerScores.size()>maximum)throw new IllegalStateException("SCORE_HOLDER_SCAN_BUDGET");return List.copyOf(playerScores.keySet());}
    @Override public Guard mineagent$objectiveGuard(Objective objective){
        if(objective==null||getObjective(objective.getName())!=objective)throw new IllegalStateException("SCORE_OBJECTIVE_MISSING");String name=objective.getName();
        if(mineagent$objectiveEpochs.size()>=4096&&!mineagent$objectiveEpochs.containsKey(name))throw new IllegalStateException("SCORE_IDENTITY_BUDGET");
        var epoch=mineagent$objectiveEpochs.computeIfAbsent(name,key->new AtomicLong());long current=epoch.get();return new Guard(current,()->epoch.get()==current);
    }
    @Unique private void mineagent$invalidateObjective(String name){var epoch=mineagent$objectiveEpochs.get(name);if(epoch!=null)epoch.incrementAndGet();}
    @Inject(method="addObjective",at=@At("RETURN"),require=1)
    private void mineagent$objectiveAdded(String name,ObjectiveCriteria criteria,Component display,ObjectiveCriteria.RenderType render,boolean autoUpdate,NumberFormat format,CallbackInfoReturnable<Objective> callback){if(callback.getReturnValue()!=null)mineagent$invalidateObjective(name);}
    @Inject(method="removeObjective",at=@At("HEAD"),require=1)
    private void mineagent$objectiveRemoved(Objective objective,CallbackInfo callback){if(objective!=null)mineagent$invalidateObjective(objective.getName());}
}
