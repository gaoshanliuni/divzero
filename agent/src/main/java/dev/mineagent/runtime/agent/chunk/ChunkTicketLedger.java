package dev.mineagent.runtime.agent.chunk;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChunkTicketLedger {
    private int radius;
    private int maximumUniqueTickets;
    private final Map<UUID, Set<ChunkCoordinate>> byAgent = new LinkedHashMap<>();
    private final Map<ChunkCoordinate, Integer> referenceCounts = new LinkedHashMap<>();
    private final Set<UUID> deniedAgents = new LinkedHashSet<>();

    public ChunkTicketLedger(int radius, int maximumUniqueTickets) {
        if (radius < 0 || radius > 1_875_000 || maximumUniqueTickets < 0) {
            throw new IllegalArgumentException("invalid ticket ledger limits");
        }
        this.radius = radius;
        this.maximumUniqueTickets = maximumUniqueTickets;
    }

    /**
     * Reallocate complete footprints in stable UUID order, once for the entire population.
     * No temporary over-budget state and no old-dimension tickets retained on denial.
     * Overlapping coordinates cost one native ticket. A zero budget removes all managed tickets.
     */
    public synchronized TicketDelta reconcile(Map<UUID, ChunkCoordinate> centers, int radius, int maximumUniqueTickets) {
        if (radius < 0 || radius > 1_875_000 || maximumUniqueTickets < 0) {
            throw new IllegalArgumentException("invalid ticket ledger limits");
        }
        var nextAgents = new LinkedHashMap<UUID, Set<ChunkCoordinate>>();
        var nextCounts = new LinkedHashMap<ChunkCoordinate, Integer>();
        var nextDenied = new LinkedHashSet<UUID>();
        for (UUID id : centers.keySet().stream().sorted().toList()) {
            var center = java.util.Objects.requireNonNull(centers.get(id), "center");
            if(footprint(radius)>maximumUniqueTickets){nextDenied.add(id);continue;}
            var desired = square(center.dimension(), center.x(), center.z(), radius);
            long additional = desired.stream().filter(c -> !nextCounts.containsKey(c)).count();
            if (nextCounts.size() + additional > maximumUniqueTickets) {
                nextDenied.add(id);
                continue;
            }
            nextAgents.put(id, desired);
            for (var coordinate : desired) nextCounts.merge(coordinate, 1, Integer::sum);
        }
        var removed = new LinkedHashSet<>(referenceCounts.keySet());
        removed.removeAll(nextCounts.keySet());
        var added = new LinkedHashSet<>(nextCounts.keySet());
        added.removeAll(referenceCounts.keySet());
        this.radius = radius;
        this.maximumUniqueTickets = maximumUniqueTickets;
        byAgent.clear(); byAgent.putAll(nextAgents);
        referenceCounts.clear(); referenceCounts.putAll(nextCounts);
        deniedAgents.clear(); deniedAgents.addAll(nextDenied);
        return new TicketDelta(true, "", added, removed);
    }

    public synchronized Set<ChunkCoordinate> coordinates() { return Set.copyOf(referenceCounts.keySet()); }
    /** Center-first allocation; pressure reduces active radius instead of refusing a whole footprint. */
    public synchronized TicketDelta reconcileFair(Map<UUID,ChunkCoordinate> centers,int radius,int budget,int round){
        if(radius<0||radius>1_875_000||budget<0)throw new IllegalArgumentException("invalid ticket ledger limits");
        var order=new java.util.ArrayList<>(centers.keySet().stream().sorted().toList());
        if(!order.isEmpty())java.util.Collections.rotate(order,-Math.floorMod(round,order.size()));
        var agents=new LinkedHashMap<UUID,Set<ChunkCoordinate>>();var counts=new LinkedHashMap<ChunkCoordinate,Integer>();
        for(var id:order)agents.put(id,new LinkedHashSet<>());
        outer:for(int ring=0;ring<=radius;ring++){
            // Enumerate one offset at a time across every AI, avoiding volume-sized intermediate squares.
            for(int dx=-ring;dx<=ring;dx++)for(int dz=-ring;dz<=ring;dz++){
                if(ring>0&&Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
                for(var id:order){var center=centers.get(id);var coordinate=new ChunkCoordinate(center.dimension(),Math.addExact(center.x(),dx),Math.addExact(center.z(),dz));
                    if(counts.containsKey(coordinate)||counts.size()<budget){agents.get(id).add(coordinate);counts.merge(coordinate,1,Integer::sum);}
                }
            }
            if(counts.size()>=budget||order.isEmpty())break outer;
        }
        var removed=new LinkedHashSet<>(referenceCounts.keySet());removed.removeAll(counts.keySet());var added=new LinkedHashSet<>(counts.keySet());added.removeAll(referenceCounts.keySet());
        this.radius=radius;maximumUniqueTickets=budget;byAgent.clear();referenceCounts.clear();referenceCounts.putAll(counts);deniedAgents.clear();
        agents.forEach((id,chunks)->{byAgent.put(id,Set.copyOf(chunks));if(chunks.isEmpty())deniedAgents.add(id);});
        return new TicketDelta(true,"",added,removed);
    }
    public synchronized boolean denied(UUID agentId) { return deniedAgents.contains(agentId); }
    public synchronized int deniedAgentCount() { return deniedAgents.size(); }

    public synchronized TicketDelta update(UUID agentId, String dimension, int centerX, int centerZ) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        if(footprint(radius)>maximumUniqueTickets){deniedAgents.add(agentId);return TicketDelta.rejected("GLOBAL_TICKET_LIMIT");}
        Set<ChunkCoordinate> desired = square(dimension, centerX, centerZ);
        Set<ChunkCoordinate> previous = byAgent.getOrDefault(agentId, Set.of());
        if (desired.equals(previous)) {
            deniedAgents.remove(agentId);
            return new TicketDelta(true, "", Set.of(), Set.of());
        }

        var simulated = new LinkedHashMap<>(referenceCounts);
        decrement(simulated, previous);
        for (ChunkCoordinate coordinate : desired) {
            simulated.merge(coordinate, 1, Integer::sum);
        }
        if (simulated.size() > maximumUniqueTickets) {
            deniedAgents.add(agentId);
            return TicketDelta.rejected("GLOBAL_TICKET_LIMIT");
        }

        var removed = new LinkedHashSet<ChunkCoordinate>();
        for (ChunkCoordinate coordinate : previous) {
            int next = referenceCounts.getOrDefault(coordinate, 0) - 1;
            if (next <= 0) {
                referenceCounts.remove(coordinate);
                removed.add(coordinate);
            } else {
                referenceCounts.put(coordinate, next);
            }
        }
        var added = new LinkedHashSet<ChunkCoordinate>();
        for (ChunkCoordinate coordinate : desired) {
            if (referenceCounts.getOrDefault(coordinate, 0) == 0) {
                added.add(coordinate);
            }
            referenceCounts.merge(coordinate, 1, Integer::sum);
        }
        byAgent.put(agentId, desired);
        deniedAgents.remove(agentId);
        return new TicketDelta(true, "", added, removed);
    }

    public synchronized TicketDelta remove(UUID agentId) {
        deniedAgents.remove(agentId);
        Set<ChunkCoordinate> previous = byAgent.remove(agentId);
        if (previous == null) {
            return new TicketDelta(true, "", Set.of(), Set.of());
        }
        var removed = new LinkedHashSet<ChunkCoordinate>();
        for (ChunkCoordinate coordinate : previous) {
            int next = referenceCounts.getOrDefault(coordinate, 0) - 1;
            if (next <= 0) {
                referenceCounts.remove(coordinate);
                removed.add(coordinate);
            } else {
                referenceCounts.put(coordinate, next);
            }
        }
        return new TicketDelta(true, "", Set.of(), removed);
    }

    public synchronized int uniqueTicketCount() {
        return referenceCounts.size();
    }

    public synchronized Set<ChunkCoordinate> chunksFor(UUID agentId) {
        return byAgent.getOrDefault(agentId, Set.of());
    }

    private Set<ChunkCoordinate> square(String dimension, int centerX, int centerZ) {
        return square(dimension, centerX, centerZ, radius);
    }
    private static long footprint(int radius){long diameter=2L*radius+1;return diameter*diameter;}

    private static Set<ChunkCoordinate> square(String dimension, int centerX, int centerZ, int radius) {
        // Use offsets so an invalid extreme center cannot wrap a loop into unbounded work.
        var chunks = new LinkedHashSet<ChunkCoordinate>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new ChunkCoordinate(dimension, Math.addExact(centerX, dx), Math.addExact(centerZ, dz)));
            }
        }
        return Set.copyOf(chunks);
    }

    private static void decrement(Map<ChunkCoordinate, Integer> counts, Set<ChunkCoordinate> coordinates) {
        for (ChunkCoordinate coordinate : coordinates) {
            int next = counts.getOrDefault(coordinate, 0) - 1;
            if (next <= 0) {
                counts.remove(coordinate);
            } else {
                counts.put(coordinate, next);
            }
        }
    }
}
