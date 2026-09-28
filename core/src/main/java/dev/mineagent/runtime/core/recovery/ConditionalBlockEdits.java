package dev.mineagent.runtime.core.recovery;

import dev.mineagent.runtime.api.recovery.BlockChange;
import dev.mineagent.runtime.api.recovery.BlockSnapshot;
import java.util.*;

/** Compare-and-write history replay. Neither undo nor redo owns later player/world changes. */
public final class ConditionalBlockEdits {
    public interface World {
        BlockSnapshot read(BlockSnapshot location) throws Exception;
        void write(BlockSnapshot value) throws Exception;
    }
    public record Result(String status,int written,List<BlockSnapshot> conflicts,String error) {
        public Result{conflicts=List.copyOf(conflicts);}
    }
    private ConditionalBlockEdits(){}
    public static Result execute(List<BlockChange> changes,boolean undo,World world){
        var steps=changes.stream().filter(c->!c.before().equals(c.after())).toList();
        var conflicts=new ArrayList<BlockSnapshot>();int written=0;
        try{
            // Reject the whole operation before any write when a known conflict already exists.
            for(var step:steps){var expected=undo?step.after():step.before();var actual=world.read(expected);if(!expected.equals(actual))conflicts.add(actual);}
            if(!conflicts.isEmpty())return new Result("CONFLICT",0,conflicts,"");
            for(var step:steps){
                var expected=undo?step.after():step.before();var target=undo?step.before():step.after();
                var actual=world.read(expected);
                if(!expected.equals(actual)){conflicts.add(actual);return new Result(written==0?"CONFLICT":"PARTIAL",written,conflicts,"");}
                // A callback can change a neighbor. Check again for every cell, never restore an old region blindly.
                try{world.write(target);}catch(Exception failure){
                    final BlockSnapshot afterFailure;
                    try{afterFailure=world.read(target);}catch(Exception unreadable){return new Result("UNKNOWN",written,List.of(),"WRITE_OUTCOME_UNKNOWN");}
                    if(!afterFailure.equals(expected))written++;
                    return new Result(written==0?"REJECTED":"PARTIAL",written,List.of(afterFailure),failure.getClass().getSimpleName());
                }
                final BlockSnapshot after;
                try{after=world.read(target);}catch(Exception unreadable){return new Result("UNKNOWN",written,List.of(),"WRITE_OUTCOME_UNKNOWN");}
                if(!after.equals(expected))written++;
                if(!target.equals(after))return new Result("PARTIAL",written,List.of(after),"WRITE_READBACK_MISMATCH");
            }
            // Later writes can cause physics changes in earlier cells.
            for(var step:steps){var target=undo?step.before():step.after();var actual=world.read(target);if(!target.equals(actual))conflicts.add(actual);}
            return new Result(conflicts.isEmpty()?"APPLIED":"PARTIAL",written,conflicts,"");
        }catch(Exception failure){return new Result(written==0?"REJECTED":"PARTIAL",written,conflicts,failure.getClass().getSimpleName());}
    }
}
