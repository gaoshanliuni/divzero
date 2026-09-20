package dev.mineagent.runtime.api.media;

import java.util.Objects;
import java.util.UUID;

public record MediaEntry(
        UUID mediaId,
        UUID worldId,
        UUID ownerPlayerId,
        MediaKind kind,
        String title,
        String sourceUrl,
        String screenBinding,
        boolean playing,
        long positionMillis,
        double playbackRate,
        long revision,
        long updatedAtEpochMillis
) {
    public MediaEntry {
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        screenBinding = screenBinding == null ? "" : screenBinding;
    }
}
