package dev.mineagent.runtime.agent.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Supported cardinal steps, including a one-block rise/drop. No vertical flight or gap jumps. */
public final class GroundPathfinder {
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    public Optional<List<GridPos>> find(GridPos start,GridPos goal,int budget,
            Predicate<GridPos> canStand,Predicate<GridPos> bodyClear){
        return new GridPathfinder().findWithNeighbors(start,goal,budget,canStand,current->{
            var result=new ArrayList<GridPos>();
            for(int[] d:DIRECTIONS)for(int dy:new int[]{0,1,-1}){
                GridPos next=current.offset(d[0],dy,d[1]);
                if(!canStand.test(next))continue;
                // Upward headroom is above the takeoff cell; downward clearance is above landing.
                if(dy>0&&!bodyClear.test(current.offset(0,1,0)))continue;
                if(dy<0&&!bodyClear.test(next.offset(0,1,0)))continue;
                result.add(next);
            }
            return result;
        });
    }
}
