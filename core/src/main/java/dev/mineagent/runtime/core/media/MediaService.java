package dev.mineagent.runtime.core.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.media.MediaEntry;
import dev.mineagent.runtime.api.media.MediaKind;
import dev.mineagent.runtime.api.media.MediaMutationResult;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MediaService implements AutoCloseable {
    private static final String NAMESPACE = "media";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private Set<String> explicitlyAllowedHosts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, MediaEntry> entries = new LinkedHashMap<>();

    private MediaService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            Set<String> explicitlyAllowedHosts
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.explicitlyAllowedHosts = normalizeHosts(explicitlyAllowedHosts);
        for (var record : repository.list(worldId, NAMESPACE)) {
            MediaEntry entry = mapper.readValue(record.payload(), MediaEntry.class);
            entries.put(entry.mediaId(), entry);
        }
    }

    public static MediaService open(
            Path database,
            UUID worldId,
            Clock clock,
            Set<String> explicitlyAllowedHosts
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new MediaService(repository, worldId, clock, explicitlyAllowedHosts);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized MediaEntry add(UUID owner, MediaKind kind, String title, String sourceUrl) throws Exception {
        validate(owner, kind, title, sourceUrl);
        UUID id = UUID.randomUUID();
        var entry = new MediaEntry(id, worldId, owner, kind, title.strip(), sourceUrl.strip(),
                "", false, 0, 1.0, 1, clock.millis());
        var saved = repository.compareAndSet(worldId, NAMESPACE, id.toString(), 0,
                mapper.writeValueAsString(entry), entry.updatedAtEpochMillis());
        if (!saved.accepted()) {
            throw new IllegalStateException("media id collision");
        }
        entries.put(id, entry);
        return entry;
    }

    public synchronized Optional<MediaEntry> get(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    public synchronized List<MediaEntry> all() {
        return entries.values().stream()
                .sorted(java.util.Comparator.comparingLong(MediaEntry::updatedAtEpochMillis).reversed()).toList();
    }

    public synchronized void setExplicitlyAllowedHosts(Set<String> hosts) {
        explicitlyAllowedHosts = normalizeHosts(hosts);
    }

    public synchronized MediaMutationResult bind(
            UUID id,
            long expectedRevision,
            boolean authorized,
            String screenBinding
    ) throws Exception {
        MediaEntry current = require(id);
        MediaMutationResult rejected = authorize(current, expectedRevision, authorized);
        if (rejected != null) {
            return rejected;
        }
        try {
            dev.mineagent.runtime.api.media.MediaScreenBinding.parse(screenBinding);
        } catch (IllegalArgumentException invalid) {
            return MediaMutationResult.rejected(current, "INVALID_SCREEN_BINDING");
        }
        return save(current, copy(current, screenBinding, current.playing(), current.positionMillis(),
                current.playbackRate()));
    }

    public synchronized MediaMutationResult updatePlayback(
            UUID id,
            long expectedRevision,
            boolean authorized,
            boolean playing,
            long positionMillis,
            double playbackRate
    ) throws Exception {
        MediaEntry current = require(id);
        MediaMutationResult rejected = authorize(current, expectedRevision, authorized);
        if (rejected != null) {
            return rejected;
        }
        if (positionMillis < 0 || !Double.isFinite(playbackRate) || playbackRate < 0.25 || playbackRate > 4.0) {
            return MediaMutationResult.rejected(current, "INVALID_PLAYBACK_STATE");
        }
        return save(current, copy(current, current.screenBinding(), playing, positionMillis, playbackRate));
    }

    private MediaMutationResult save(MediaEntry current, MediaEntry next) throws Exception {
        var saved = repository.compareAndSet(worldId, NAMESPACE, current.mediaId().toString(),
                current.revision(), mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!saved.accepted()) {
            return MediaMutationResult.rejected(current, "STALE_REVISION");
        }
        entries.put(next.mediaId(), next);
        return MediaMutationResult.accepted(next);
    }

    private MediaEntry copy(
            MediaEntry entry,
            String binding,
            boolean playing,
            long position,
            double rate
    ) {
        return new MediaEntry(entry.mediaId(), entry.worldId(), entry.ownerPlayerId(), entry.kind(), entry.title(),
                entry.sourceUrl(), binding, playing, position, rate, entry.revision() + 1, clock.millis());
    }

    private MediaMutationResult authorize(MediaEntry current, long revision, boolean authorized) {
        if (!authorized) {
            return MediaMutationResult.rejected(current, "FORBIDDEN");
        }
        return current.revision() == revision ? null
                : MediaMutationResult.rejected(current, "STALE_REVISION");
    }

    private MediaEntry require(UUID id) {
        return get(id).orElseThrow(() -> new IllegalArgumentException("unknown media"));
    }

    private void validate(UUID owner, MediaKind kind, String title, String sourceUrl) {
        if (owner == null || kind == null || title == null || title.isBlank() || title.length() > 256
                || sourceUrl == null || sourceUrl.length() > 4_096) {
            throw new IllegalArgumentException("invalid media entry");
        }
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid media URL", invalid);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || host == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("media URL must be HTTP(S)");
        }
        String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
        if (!explicitlyAllowedHosts.contains(normalizedHost) && isPrivateHost(normalizedHost)) {
            throw new IllegalArgumentException("media URL targets a private network");
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.equals("::1") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        if (hosts == null || hosts.size() > 64 || hosts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("invalid media allowed hosts");
        }
        var normalized = hosts.stream().map(String::strip)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (normalized.stream().anyMatch(value -> value.length() > 253
                || !(value.matches("[a-z0-9.-]+") || value.matches("[a-f0-9:]+")))) {
            throw new IllegalArgumentException("invalid media allowed host");
        }
        return normalized;
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
