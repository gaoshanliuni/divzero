package dev.mineagent.runtime.worker.media;

public record ResolvedMedia(
        String id,
        String title,
        String webpageUrl,
        String mediaUrl,
        String extension,
        String protocol,
        long durationMillis
) {
}
