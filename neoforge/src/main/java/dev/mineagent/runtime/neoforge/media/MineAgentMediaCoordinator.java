package dev.mineagent.runtime.neoforge.media;

import dev.mineagent.runtime.api.media.MediaEntry;
import dev.mineagent.runtime.api.media.MediaKind;
import dev.mineagent.runtime.api.media.MediaScreenBinding;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MineAgentMediaCoordinator {
    private static final long FRAME_INTERVAL_MILLIS = 500;
    private static final long AUDIO_SEGMENT_MILLIS = 5_000;

    private final MinecraftServer server;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    public MineAgentMediaCoordinator(MinecraftServer server) {
        this.server = java.util.Objects.requireNonNull(server, "server");
    }

    public void start(MediaEntry entry) {
        if (entry == null || !entry.playing()) {
            throw new IllegalArgumentException("playing media entry is required");
        }
        final MediaScreenBinding binding;
        try {
            binding = MediaScreenBinding.parse(entry.screenBinding());
        } catch (IllegalArgumentException invalid) {
            MineAgentRuntimeMod.LOGGER.warn("Cannot play unbound media {}", entry.mediaId());
            return;
        }
        var session = new Session(entry.mediaId(), entry.revision(), entry.kind(), entry.sourceUrl(), binding);
        sessions.put(entry.mediaId(), session);
        resolve(session, true);
    }

    public void stop(UUID mediaId) {
        sessions.remove(mediaId);
    }

    public void synchronizeClient() {
        for (Session session : sessions.values()) {
            if (session.kind == MediaKind.IMAGE) {
                session.imageRendered = false;
                session.nextFrameAt = 0;
            }
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Session session : java.util.List.copyOf(sessions.values())) {
            MediaEntry entry = MineAgentRuntimeServices.media(server).get(session.mediaId).orElse(null);
            if (entry == null || !entry.playing() || entry.revision() != session.revision) {
                sessions.remove(session.mediaId);
                continue;
            }
            if (!session.ready) {
                continue;
            }
            long position = session.kind == MediaKind.IMAGE
                    ? 0 : effectivePosition(entry, now, session.durationMillis);
            if (session.hasVideo && !session.frameInFlight && now >= session.nextFrameAt
                    && !(session.kind == MediaKind.IMAGE && session.imageRendered)) {
                session.frameInFlight = true;
                session.nextFrameAt = now + FRAME_INTERVAL_MILLIS;
                MineAgentRuntimeServices.worker(server)
                        .renderMediaFrame(session.source, position, 512, 288, allowedHosts())
                        .whenComplete((response, failure) -> server.execute(() -> {
                            Session current = sessions.get(session.mediaId);
                            if (current != session) {
                                return;
                            }
                            current.frameInFlight = false;
                            if (failure == null && response != null && "media.frame.result".equals(response.type())) {
                                try {
                                    String hash = String.valueOf(response.payload().get("sha256"));
                                    byte[] bytes = MineAgentRuntimeServices.worker(server).contentBytes(hash);
                                    MineAgentNetwork.sendMediaFrame(server, entry, position, hash, bytes);
                                    current.imageRendered = current.kind == MediaKind.IMAGE;
                                    current.frameFailures = 0;
                                } catch (Exception transferFailure) {
                                    MineAgentRuntimeMod.LOGGER.warn("Failed to transfer media frame {}",
                                            session.mediaId, transferFailure);
                                }
                            } else {
                                if (++current.frameFailures >= 3) {
                                    sessions.remove(session.mediaId);
                                }
                                MineAgentRuntimeMod.LOGGER.warn("FFmpeg frame failed for {}: {}", session.mediaId,
                                        failure == null && response != null ? response.payload() : failure);
                            }
                        }));
            }
            if (session.hasAudio && !session.audioInFlight && now >= session.nextAudioAt) {
                session.audioInFlight = true;
                session.nextAudioAt = now + AUDIO_SEGMENT_MILLIS;
                MineAgentRuntimeServices.worker(server)
                        .renderMediaAudio(session.source, position, AUDIO_SEGMENT_MILLIS, allowedHosts())
                        .whenComplete((response, failure) -> server.execute(() -> {
                            Session current = sessions.get(session.mediaId);
                            if (current != session) {
                                return;
                            }
                            current.audioInFlight = false;
                            if (failure == null && response != null && "media.audio.result".equals(response.type())) {
                                try {
                                    String hash = String.valueOf(response.payload().get("sha256"));
                                    byte[] bytes = MineAgentRuntimeServices.worker(server).contentBytes(hash);
                                    MineAgentNetwork.sendMediaAudio(server, entry, hash, bytes);
                                    current.audioFailures = 0;
                                } catch (Exception transferFailure) {
                                    MineAgentRuntimeMod.LOGGER.warn("Failed to transfer media audio {}",
                                            session.mediaId, transferFailure);
                                }
                            } else {
                                if (++current.audioFailures >= 3) {
                                    current.hasAudio = false;
                                }
                                MineAgentRuntimeMod.LOGGER.warn("FFmpeg audio failed for {}: {}", session.mediaId,
                                        failure == null && response != null ? response.payload() : failure);
                            }
                        }));
            }
        }
    }

    private void resolve(Session session, boolean allowFallback) {
        MineAgentRuntimeServices.worker(server).resolveMedia(session.originalSource, allowedHosts())
                .whenComplete((response, failure) -> server.execute(() -> {
                    if (sessions.get(session.mediaId) != session) {
                        return;
                    }
                    if (failure == null && response != null && "media.resolve.result".equals(response.type())) {
                        session.source = String.valueOf(response.payload().getOrDefault("mediaUrl", ""));
                        probe(session);
                    } else if (allowFallback) {
                        session.source = session.originalSource;
                        probe(session);
                    } else {
                        sessions.remove(session.mediaId);
                    }
                }));
    }

    private void probe(Session session) {
        MineAgentRuntimeServices.worker(server).probeMedia(session.source, allowedHosts())
                .whenComplete((response, failure) -> server.execute(() -> {
                    if (sessions.get(session.mediaId) != session) {
                        return;
                    }
                    if (failure != null || response == null || !"media.probe.result".equals(response.type())) {
                        sessions.remove(session.mediaId);
                        MineAgentRuntimeMod.LOGGER.warn("FFprobe failed for {}: {}", session.mediaId,
                                failure == null && response != null ? response.payload() : failure);
                        return;
                    }
                    session.durationMillis = Math.max(0, number(response.payload().get("durationMillis")));
                    session.hasVideo = Boolean.parseBoolean(String.valueOf(
                            response.payload().getOrDefault("hasVideo", false)));
                    session.hasAudio = Boolean.parseBoolean(String.valueOf(
                            response.payload().getOrDefault("hasAudio", false)));
                    session.nextFrameAt = 0;
                    session.nextAudioAt = 0;
                    session.ready = session.hasVideo || session.hasAudio;
                }));
    }

    private static long effectivePosition(MediaEntry entry, long now, long duration) {
        long elapsed = entry.playing() ? Math.max(0, now - entry.updatedAtEpochMillis()) : 0;
        long position;
        try {
            position = Math.addExact(entry.positionMillis(), Math.round(elapsed * entry.playbackRate()));
        } catch (ArithmeticException overflow) {
            position = Long.MAX_VALUE;
        }
        return duration > 0 ? Math.floorMod(position, duration) : Math.max(0, position);
    }

    private String allowedHosts() {
        String configured = MineAgentRuntimeServices.config(server).snapshot().values()
                .getOrDefault("media.allowedHosts", "");
        if (!Boolean.getBoolean("mineagent.multiplayerSmokeServer")) {
            return configured;
        }
        return configured.isBlank() ? "127.0.0.1" : configured + ",127.0.0.1";
    }

    private static long number(Object value) {
        try {
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException invalid) {
            return 0;
        }
    }

    private static final class Session {
        private final UUID mediaId;
        private final long revision;
        private final MediaKind kind;
        private final String originalSource;
        private final MediaScreenBinding binding;
        private String source;
        private long durationMillis;
        private long nextFrameAt;
        private long nextAudioAt;
        private boolean ready;
        private boolean hasVideo;
        private boolean hasAudio;
        private boolean frameInFlight;
        private boolean audioInFlight;
        private boolean imageRendered;
        private int frameFailures;
        private int audioFailures;

        private Session(UUID mediaId, long revision, MediaKind kind, String originalSource, MediaScreenBinding binding) {
            this.mediaId = mediaId;
            this.revision = revision;
            this.kind = kind;
            this.originalSource = originalSource;
            this.binding = binding;
        }
    }
}
