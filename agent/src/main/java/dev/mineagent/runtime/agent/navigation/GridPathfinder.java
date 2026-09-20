package dev.mineagent.runtime.agent.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

public final class GridPathfinder {
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    public Optional<List<GridPos>> find(
            GridPos start,
            GridPos goal,
            int maximumExpandedNodes,
            Predicate<GridPos> traversable
    ) {
        return findWithNeighbors(start,goal,maximumExpandedNodes,traversable,current->{
            var next=new ArrayList<GridPos>();
            for(int[] d:DIRECTIONS)next.add(current.offset(d[0],d[1],d[2]));
            return next;
        });
    }

    Optional<List<GridPos>> findWithNeighbors(GridPos start,GridPos goal,int maximumExpandedNodes,
            Predicate<GridPos> traversable,java.util.function.Function<GridPos,List<GridPos>> neighbors) {
        if (maximumExpandedNodes < 1) {
            throw new IllegalArgumentException("maximumExpandedNodes must be positive");
        }
        if (!traversable.test(start) || !traversable.test(goal)) {
            return Optional.empty();
        }

        var open = new PriorityQueue<NodeScore>(Comparator
                .comparingInt(NodeScore::estimatedTotal)
                .thenComparingInt(NodeScore::cost));
        var costs = new HashMap<GridPos, Integer>();
        var previous = new HashMap<GridPos, GridPos>();
        var closed = new HashSet<GridPos>();
        costs.put(start, 0);
        open.add(new NodeScore(start, 0, start.manhattanDistance(goal)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < maximumExpandedNodes) {
            GridPos current = open.remove().position();
            if (!closed.add(current)) {
                continue;
            }
            expanded++;
            if (current.equals(goal)) {
                return Optional.of(reconstruct(previous, goal));
            }

            for (GridPos next : neighbors.apply(current)) {
                if (closed.contains(next) || !traversable.test(next)) {
                    continue;
                }
                int nextCost = costs.get(current) + current.manhattanDistance(next);
                if (nextCost < costs.getOrDefault(next, Integer.MAX_VALUE)) {
                    costs.put(next, nextCost);
                    previous.put(next, current);
                    open.add(new NodeScore(next, nextCost, nextCost + next.manhattanDistance(goal)));
                }
            }
        }
        return Optional.empty();
    }

    private static List<GridPos> reconstruct(Map<GridPos, GridPos> previous, GridPos goal) {
        var reversed = new ArrayList<GridPos>();
        GridPos current = goal;
        reversed.add(current);
        while (previous.containsKey(current)) {
            current = previous.get(current);
            reversed.add(current);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private record NodeScore(GridPos position, int cost, int estimatedTotal) {
    }
}
