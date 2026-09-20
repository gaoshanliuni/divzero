package dev.mineagent.runtime.core.scoreboard;

record ScoreboardLedgerRecord(
        String fingerprint,
        boolean accepted,
        String errorCode,
        long resultRevision,
        long completedAtEpochMillis
) {
}
