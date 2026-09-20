package dev.mineagent.runtime.worker.media;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;

public final class YtDlpMediaResolver {
    private final CommandRunner runner;
    private final ObjectMapper mapper = new ObjectMapper();

    public YtDlpMediaResolver(CommandRunner runner) {
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
    }

    public ResolvedMedia resolve(String sourceUrl) {
        requireHttpUrl(sourceUrl, "source URL");
        ToolResult result = runner.run(List.of(
                "--dump-single-json", "--no-playlist", "--no-warnings", "--", sourceUrl));
        if (result.exitCode() != 0) {
            throw new MediaToolException("yt-dlp failed: " + result.stderr().strip());
        }
        try {
            var json = mapper.readTree(result.stdout());
            String mediaUrl = required(json.path("url").asText(""), "resolved media URL");
            requireHttpUrl(mediaUrl, "resolved media URL");
            double duration = json.path("duration").asDouble(0);
            return new ResolvedMedia(
                    json.path("id").asText(""),
                    required(json.path("title").asText(""), "title"),
                    json.path("webpage_url").asText(sourceUrl),
                    mediaUrl,
                    json.path("ext").asText(""),
                    json.path("protocol").asText(""),
                    Math.max(0, Math.round(duration * 1_000))
            );
        } catch (MediaToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MediaToolException("invalid yt-dlp JSON", failure);
        }
    }

    private static void requireHttpUrl(String value, String description) {
        try {
            URI uri = URI.create(required(value, description));
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new MediaToolException(description + " must be HTTP(S)");
            }
        } catch (IllegalArgumentException invalid) {
            throw new MediaToolException("invalid " + description, invalid);
        }
    }

    private static String required(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new MediaToolException("missing " + description);
        }
        return value;
    }
}
