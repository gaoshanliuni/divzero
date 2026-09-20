package dev.mineagent.runtime.agent.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

public final class BoundedVeinPlanner {
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    public List<GridPos> find(GridPos start, int maximumBlocks, Predicate<GridPos> matches) {
        if (start == null || maximumBlocks < 1 || maximumBlocks > 128 || matches == null) {
            throw new IllegalArgumentException("invalid vein plan");
        }
        if (!matches.test(start)) {
            return List.of();
        }
        var result = new ArrayList<GridPos>(maximumBlocks);
        var queue = new ArrayDeque<GridPos>();
        var seen = new HashSet<GridPos>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && result.size() < maximumBlocks && seen.size() <= maximumBlocks * 16) {
            GridPos current = queue.removeFirst();
            if (!matches.test(current)) {
                continue;
            }
            result.add(current);
            for (int[] direction : DIRECTIONS) {
                GridPos next = current.offset(direction[0], direction[1], direction[2]);
                if (seen.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return List.copyOf(result);
    }
}
