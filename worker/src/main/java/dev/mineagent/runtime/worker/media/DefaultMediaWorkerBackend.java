package dev.mineagent.runtime.worker.media;

import dev.mineagent.runtime.core.content.ContentAddressedStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class DefaultMediaWorkerBackend implements MediaWorkerBackend {
    private final YtDlpMediaResolver resolver;
    private final FfmpegMediaProcessor processor;

    private DefaultMediaWorkerBackend(YtDlpMediaResolver resolver, FfmpegMediaProcessor processor) {
        this.resolver = resolver;
        this.processor = processor;
    }

    public static DefaultMediaWorkerBackend install(Path contentRoot, ContentAddressedStore store) {
        MediaToolInstallation tools = BundledMediaTools.windowsX64(contentRoot.resolve(".media-tools"))
                .ensureInstalled();
        var ytDlp = new ExternalToolRunner(List.of(tools.ytDlp().toString()),
                Duration.ofSeconds(90), 2 * 1024 * 1024);
        return new DefaultMediaWorkerBackend(new YtDlpMediaResolver(ytDlp),
                FfmpegMediaProcessor.production(tools, store, contentRoot.resolve(".media-work")));
    }

    @Override
    public ResolvedMedia resolve(String sourceUrl) {
        return resolver.resolve(sourceUrl);
    }

    @Override
    public MediaProbe probe(String source) {
        return processor.probe(source);
    }

    @Override
    public MediaArtifact renderFrame(String source, long positionMillis, int width, int height) {
        return processor.renderFrame(source, positionMillis, width, height);
    }

    @Override
    public MediaArtifact renderAudio(String source, long positionMillis, long durationMillis) {
        return processor.renderAudio(source, positionMillis, durationMillis);
    }
}
