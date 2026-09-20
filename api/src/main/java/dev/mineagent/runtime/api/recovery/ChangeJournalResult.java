package dev.mineagent.runtime.api.recovery;

import java.util.Objects;

public record ChangeJournalResult(boolean accepted, String errorCode, ChangeJournalEntry entry) {
    public ChangeJournalResult {
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(entry, "entry");
    }

    public static ChangeJournalResult accepted(ChangeJournalEntry entry) {
        return new ChangeJournalResult(true, "", entry);
    }

    public static ChangeJournalResult rejected(ChangeJournalEntry entry, String errorCode) {
        return new ChangeJournalResult(false, errorCode, entry);
    }
}
