package dev.mineagent.runtime.core.task;
import java.util.Optional;

/** Exactly one bounded input frame per client tick. Cancelling never rewinds or replays a step. */
public final class PlayerControlSequence {
    public record Frame(PlayerControlPlan.Step step,int index,int tick,boolean first,boolean last) {}
    private final PlayerControlPlan plan;
    private int index,tick,elapsed;private boolean stopped;
    public PlayerControlSequence(PlayerControlPlan plan){this.plan=java.util.Objects.requireNonNull(plan);}
    public Optional<Frame> next(){
        if(stopped||index>=plan.steps().size())return Optional.empty();
        var step=plan.steps().get(index);var frame=new Frame(step,index,tick,tick==0,index==plan.steps().size()-1&&tick==step.ticks()-1);
        elapsed++;if(++tick==step.ticks()){index++;tick=0;}return Optional.of(frame);
    }
    public int completedSteps(){return index;}
    public int elapsedTicks(){return elapsed;}
    public void stop(){stopped=true;}
}
