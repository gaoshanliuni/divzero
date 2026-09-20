package dev.mineagent.runtime.worker.media;

import java.nio.file.Path;
import java.util.Objects;

public record MediaToolInstallation(Path root, Path ytDlp, Path ffmpeg, Path ffprobe, Path license) {
    public MediaToolInstallation {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(ytDlp, "ytDlp");
        Objects.requireNonNull(ffmpeg, "ffmpeg");
        Objects.requireNonNull(ffprobe, "ffprobe");
        Objects.requireNonNull(license, "license");
    }
}
