package dev.mineagent.runtime.core.config;

import java.util.Map;

/** Admission limits, not an instruction to delete persisted players or their native NBT. */
public record RuntimeResourceLimits(int maxAgents, int maxChunkTickets, int agentTicketRadius) {
    public static final RuntimeResourceLimits DEFAULT = new RuntimeResourceLimits(Integer.MAX_VALUE, 100, 2);

    public RuntimeResourceLimits {
        if (maxAgents < 1 || maxChunkTickets < 0 || maxChunkTickets > 100
                || agentTicketRadius < 0 || agentTicketRadius > 2) {
            throw new IllegalArgumentException("RUNTIME_RESOURCE_LIMITS_INVALID");
        }
    }

    public static RuntimeResourceLimits from(Map<String, String> values) {
        try {
            return new RuntimeResourceLimits(
                    Integer.parseInt(values.getOrDefault("runtime.maxAgents", Integer.toString(Integer.MAX_VALUE))),
                    Integer.parseInt(values.getOrDefault("runtime.maxChunkTickets", "100")),
                    Integer.parseInt(values.getOrDefault("runtime.agentTicketRadius", "2")));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("RUNTIME_RESOURCE_LIMITS_INVALID", failure);
        }
    }

    public int ticketsPerAgent() {
        int diameter = 2 * agentTicketRadius + 1;
        return diameter * diameter;
    }
}
