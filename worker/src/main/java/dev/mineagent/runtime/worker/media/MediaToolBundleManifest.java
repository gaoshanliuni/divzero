package dev.mineagent.runtime.worker.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record MediaToolBundleManifest(
        String bundleId,
        String ytDlpResource,
        long ytDlpBytes,
        String ffmpegArchiveResource,
        long ffmpegArchiveBytes,
        String ffmpegArchiveRoot,
        Map<String, Long> extractedBytes
) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,160}");

    public MediaToolBundleManifest {
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(ytDlpResource, "ytDlpResource");
        Objects.requireNonNull(ffmpegArchiveResource, "ffmpegArchiveResource");
        Objects.requireNonNull(ffmpegArchiveRoot, "ffmpegArchiveRoot");
        Objects.requireNonNull(extractedBytes, "extractedBytes");
        if (!ID.matcher(bundleId).matches() || !ID.matcher(ffmpegArchiveRoot).matches()
                || ytDlpResource.isBlank() || ffmpegArchiveResource.isBlank()
                || ytDlpBytes<1
                || ffmpegArchiveBytes<1) {
            throw new IllegalArgumentException("invalid media tool bundle manifest");
        }
        var verified = new LinkedHashMap<String, Long>();
        extractedBytes.forEach((path, size) -> {
            if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                    || path.contains("..") || path.contains(":") || size == null
                    || size<1) {
                throw new IllegalArgumentException("invalid bundled tool file");
            }
            verified.put(path.replace('\\', '/'), size);
        });
        if (!verified.containsKey("bin/ffmpeg.exe") || !verified.containsKey("bin/ffprobe.exe")
                || !verified.containsKey("LICENSE.txt")) {
            throw new IllegalArgumentException("media bundle must verify FFmpeg, FFprobe and license");
        }
        extractedBytes = Map.copyOf(verified);
    }
}
