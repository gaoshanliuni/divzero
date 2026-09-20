package dev.mineagent.runtime.neoforge.scoreboard;

import net.minecraft.world.scores.Objective;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Read-only bounded Native access; its guard is safe to check away from the server thread. */
public interface ScoreboardReadWindow {
    record Guard(long generation,BooleanSupplier current){}
    List<String> mineagent$scoreHolderNames(int maximum);
    Guard mineagent$objectiveGuard(Objective objective);
}
