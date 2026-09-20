package dev.mineagent.runtime.neoforge.network;

import dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger;

import java.util.Objects;

record AppearanceNetworkResponse(
        MineAgentPayloads.AppearanceState state,
        boolean refreshPanelSnapshot
) {
    static AppearanceNetworkResponse from(
            String agentId,
            String requestId,
            AppearanceRequestLedger.Outcome outcome,
            long revisionBeforeCommand
    ) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(outcome, "outcome");
        return new AppearanceNetworkResponse(
                new MineAgentPayloads.AppearanceState(
                        agentId, requestId, outcome.accepted(), outcome.errorCode(), outcome.revision()),
                outcome.accepted() || outcome.revision() != revisionBeforeCommand
        );
    }
}
