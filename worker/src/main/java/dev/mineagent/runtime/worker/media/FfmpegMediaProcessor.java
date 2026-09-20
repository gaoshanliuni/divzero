package dev.mineagent.runtime.worker.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.content.ContentAddressedStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FfmpegMediaProcessor {
    private static final long MAX_POSITION_MILLIS = Duration.ofDays(7).toMillis();
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;

    private final CommandRunner probeRunner;
    private final CommandRunner ffmpegRunner;
    private final ContentAddressedStore store;
    private final Path workDirectory;
    private final ObjectMapper mapper = new ObjectMapper();

    public FfmpegMediaProcessor(
            CommandRunner probeRunner,
            CommandRunner ffmpegRunner,
            ContentAddressedStore store,
            Path workDirectory
    ) {
        this.probeRunner = Objects.requireNonNull(probeRunner, "probeRunner");
        this.ffmpegRunner = Objects.requireNonNull(ffmpegRunner, "ffmpegRunner");
        this.store = Objects.requireNonNull(store, "store");
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory").toAbsolutePath().normalize();
    }

    public static FfmpegMediaProcessor production(
            MediaToolInstallation tools,
            ContentAddressedStore store,
            Path workDirectory
    ) {
        Objects.requireNonNull(tools, "tools");
        return new FfmpegMediaProcessor(
                new ExternalToolRunner(List.of(tools.ffprobe().toString()), Duration.ofSeconds(45), 2 * 1024 * 1024),
                new ExternalToolRunner(List.of(tools.ffmpeg().toString()), Duration.ofSeconds(60), 2 * 1024 * 1024),
                store, workDirectory);
    }

    public MediaProbe probe(String source) {
        requireSource(source);
        ToolResult result = probeRunner.run(List.of(
                "-v", "error", "-print_format", "json", "-show_format", "-show_streams", "--", source));
        if (result.exitCode() != 0) {
            throw toolFailure("FFprobe", result);
        }
        try {
            JsonNode root = mapper.readTree(result.stdout());
            JsonNode format = root.path("format");
            long duration = Math.max(0, Math.round(number(format.path("duration")) * 1_000));
            int width = 0;
            int height = 0;
            boolean video = false;
            boolean audio = false;
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    video = true;
                    width = Math.max(width, stream.path("width").asInt(0));
                    height = Math.max(height, stream.path("height").asInt(0));
                } else if ("audio".equals(stream.path("codec_type").asText())) {
                    audio = true;
                }
            }
            if (!video && !audio) {
                throw new MediaToolException("FFprobe returned no playable streams");
            }
            return new MediaProbe(format.path("format_name").asText(""), duration, width, height, video, audio);
        } catch (MediaToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MediaToolException("invalid FFprobe JSON", failure);
        }
    }

    public MediaArtifact renderFrame(String source, long positionMillis, int width, int height) {
        requireSource(source);
        requirePosition(positionMillis);
        if (width < 16 || width > 1_920 || height < 16 || height > 1_080) {
            throw new IllegalArgumentException("frame dimensions are out of range");
        }
        return render("frame-", ".png", MAX_FRAME_BYTES, "image/png", List.of(
                "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", seconds(positionMillis),
                "-i", source, "-frames:v", "1", "-an", "-vf",
                "scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease",
                "-f", "image2", "-y"));
    }

    public MediaArtifact renderAudio(String source, long positionMillis, long durationMillis) {
        requireSource(source);
        requirePosition(positionMillis);
        if (durationMillis < 100 || durationMillis > 10_000) {
            throw new IllegalArgumentException("audio segment duration is out of range");
        }
        return render("audio-", ".mp3", MAX_AUDIO_BYTES, "audio/mpeg", List.of(
                "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", seconds(positionMillis),
                "-i", source, "-t", seconds(durationMillis), "-vn", "-ac", "2", "-ar", "44100",
                "-b:a", "128k", "-f", "mp3", "-y"));
    }

    private MediaArtifact render(
            String prefix,
            String suffix,
            int maximumBytes,
            String contentType,
            List<String> arguments
    ) {
        Path output = null;
        try {
            Files.createDirectories(workDirectory);
            output = Files.createTempFile(workDirectory, prefix, suffix);
            var command = new java.util.ArrayList<>(arguments);
            command.add(output.toString());
            ToolResult result = ffmpegRunner.run(command);
            if (result.exitCode() != 0) {
                throw toolFailure("FFmpeg", result);
            }
            long size = Files.size(output);
            if (size < 1 || size > maximumBytes) {
                throw new MediaToolException("FFmpeg artifact size is out of range");
            }
            var stored = store.put(Files.readAllBytes(output));
            return new MediaArtifact(stored.sha256(), stored.size(), contentType);
        } catch (MediaToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MediaToolException("cannot create media artifact", failure);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static double number(JsonNode value) {
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText("0"));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1_000.0);
    }

    private static void requirePosition(long positionMillis) {
        if (positionMillis < 0 || positionMillis > MAX_POSITION_MILLIS) {
            throw new IllegalArgumentException("media position is out of range");
        }
    }

    private static void requireSource(String source) {
        if (source == null || source.isBlank() || source.length() > 16_384 || source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid media source");
        }
    }

    private static MediaToolException toolFailure(String tool, ToolResult result) {
        String detail = result.stderr() == null ? "" : result.stderr().strip();
        if (detail.length() > 2_000) {
            detail = detail.substring(0, 2_000);
        }
        return new MediaToolException(tool + " failed with exit " + result.exitCode() + ": " + detail);
    }
}
