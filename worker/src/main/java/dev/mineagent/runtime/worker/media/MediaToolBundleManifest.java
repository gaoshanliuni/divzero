package dev.mineagent.runtime.worker.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record MediaToolBundleManifest(
        String bundleId,
        String ytDlpResource,
        String ytDlpSha256,
        String ffmpegArchiveResource,
        String ffmpegArchiveSha256,
        String ffmpegArchiveRoot,
        Map<String, String> extractedSha256
) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,160}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public MediaToolBundleManifest {
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(ytDlpResource, "ytDlpResource");
        Objects.requireNonNull(ytDlpSha256, "ytDlpSha256");
        Objects.requireNonNull(ffmpegArchiveResource, "ffmpegArchiveResource");
        Objects.requireNonNull(ffmpegArchiveSha256, "ffmpegArchiveSha256");
        Objects.requireNonNull(ffmpegArchiveRoot, "ffmpegArchiveRoot");
        Objects.requireNonNull(extractedSha256, "extractedSha256");
        if (!ID.matcher(bundleId).matches() || !ID.matcher(ffmpegArchiveRoot).matches()
                || ytDlpResource.isBlank() || ffmpegArchiveResource.isBlank()
                || !SHA256.matcher(ytDlpSha256).matches()
                || !SHA256.matcher(ffmpegArchiveSha256).matches()) {
            throw new IllegalArgumentException("invalid media tool bundle manifest");
        }
        var verified = new LinkedHashMap<String, String>();
        extractedSha256.forEach((path, hash) -> {
            if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                    || path.contains("..") || path.contains(":") || hash == null
                    || !SHA256.matcher(hash).matches()) {
                throw new IllegalArgumentException("invalid bundled tool file");
            }
            verified.put(path.replace('\\', '/'), hash);
        });
        if (!verified.containsKey("bin/ffmpeg.exe") || !verified.containsKey("bin/ffprobe.exe")
                || !verified.containsKey("LICENSE.txt")) {
            throw new IllegalArgumentException("media bundle must verify FFmpeg, FFprobe and license");
        }
        extractedSha256 = Map.copyOf(verified);
    }
}
