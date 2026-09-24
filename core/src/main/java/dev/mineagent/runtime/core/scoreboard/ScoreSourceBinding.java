package dev.mineagent.runtime.core.scoreboard;

import java.util.Objects;
import java.util.UUID;

public record ScoreSourceBinding(
        UUID sourceId,
        ScoreSourceBackend backend,
        String reference,
        ScoreSourceOwnership ownership,
        ScoreAccessMode accessMode,
        UUID ownerPackageId,
        long revision,
        long updatedAtEpochMillis
) {
    public ScoreSourceBinding {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(backend, "backend");
        boolean validReference = backend == ScoreSourceBackend.VANILLA
                ? dev.mineagent.runtime.api.scoreboard.NativeScoreboardText.valid(reference)
                : reference != null && !reference.isBlank() && reference.length() <= 256;
        if (!validReference || revision < 1) {
            throw new IllegalArgumentException("invalid score source binding");
        }
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(accessMode, "accessMode");
        if (ownership == ScoreSourceOwnership.RUNTIME && ownerPackageId == null) {
            throw new IllegalArgumentException("runtime score source requires owner package");
        }
    }
}
