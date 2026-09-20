package dev.mineagent.runtime.worker.media;

public interface MediaWorkerBackend {
    ResolvedMedia resolve(String sourceUrl);

    MediaProbe probe(String source);

    MediaArtifact renderFrame(String source, long positionMillis, int width, int height);

    MediaArtifact renderAudio(String source, long positionMillis, long durationMillis);
}
