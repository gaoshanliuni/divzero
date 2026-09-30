package dev.mineagent.runtime.worker.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipInputStream;

public final class BundledMediaTools {
    private static final int MAX_ARCHIVE_ENTRIES = 2_048;
    private static final long MAX_EXTRACTED_BYTES = 1_000_000_000L;

    private final Path installRoot;
    private final ClassLoader resources;
    private final MediaToolBundleManifest manifest;
    private final String osName;
    private final String osArch;

    public static BundledMediaTools windowsX64(Path installRoot) {
        var hashes = java.util.Map.ofEntries(
                java.util.Map.entry("LICENSE.txt", "da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768"),
                java.util.Map.entry("bin/avcodec-62.dll", "f02f4c3a5fb0f5c657035cace8212d592d5f59acf2497709f8403bb05f0a1e97"),
                java.util.Map.entry("bin/avdevice-62.dll", "eeac814d1faf3d734b57b5c3a027a8f860d7db76ff83bde9407121b037582865"),
                java.util.Map.entry("bin/avfilter-11.dll", "0581a361ac97a02460cb9bc8325b5044e80febd154b8ac651ca6354895dc72b2"),
                java.util.Map.entry("bin/avformat-62.dll", "4a93cca2fbd57663ceb071a453efea665aec82f91c78d7fa05e26eaf93e307b3"),
                java.util.Map.entry("bin/avutil-60.dll", "a570924bc67ebaee8ffe64f1617d3cb6e4b0019baf768749d48890351bbdd916"),
                java.util.Map.entry("bin/ffmpeg.exe", "c3934c14d44f7172aae94fe4c682bcfcdc1e48f1f857ca1b165110e408bdb4f6"),
                java.util.Map.entry("bin/ffprobe.exe", "a71d1a9aec02c9a8e8ec2a94f1de273e36e1ed180904ea943b6ed597cbe6e2ce"),
                java.util.Map.entry("bin/swresample-6.dll", "529ac5de01212cf7030d138fbd03858b993f8344fbc5088c31dc3ba9689464d6"),
                java.util.Map.entry("bin/swscale-9.dll", "6acc42d25bd870b2f95e0183e23ebdd214edd2472eede729cda6ffba296c860a")
        );
        var manifest = new MediaToolBundleManifest(
                "windows-x64-ffmpeg-8.1.3-yt-dlp-2026.08.19",
                "META-INF/mineagent/tools/yt-dlp-2026.08.19.exe",
                "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a",
                "META-INF/mineagent/tools/ffmpeg-n8.1.3-6-gff48edd8b2-win64-lgpl-shared-8.1.zip",
                "1c9af2356443fec537fe1a64a5b33cb4c54fa212ad6590464423b3437e1aaa44",
                "ffmpeg-n8.1.3-6-gff48edd8b2-win64-lgpl-shared-8.1", hashes);
        return new BundledMediaTools(installRoot, BundledMediaTools.class.getClassLoader(), manifest,
                System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public BundledMediaTools(
            Path installRoot,
            ClassLoader resources,
            MediaToolBundleManifest manifest,
            String osName,
            String osArch
    ) {
        this.installRoot = Objects.requireNonNull(installRoot, "installRoot").toAbsolutePath().normalize();
        this.resources = Objects.requireNonNull(resources, "resources");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.osName = Objects.requireNonNull(osName, "osName");
        this.osArch = Objects.requireNonNull(osArch, "osArch");
    }

    public synchronized MediaToolInstallation ensureInstalled() {
        requireWindowsX64();
        Path target = installRoot.resolve(manifest.bundleId()).normalize();
        if (!target.getParent().equals(installRoot)) {
            throw new MediaToolException("invalid media tool installation target");
        }
        try {
            Files.createDirectories(installRoot);
            if (verify(target)) {
                return installation(target);
            }
            Path stage = Files.createTempDirectory(installRoot, ".mineagent-media-tools-");
            Path replaced = null;
            try {
                installInto(stage);
                if (!verify(stage)) {
                    throw new MediaToolException("bundled media tool verification failed after extraction");
                }
                if (Files.exists(target)) {
                    replaced = installRoot.resolve(".invalid-" + UUID.randomUUID()).normalize();
                    move(target, replaced);
                }
                move(stage, target);
                if (replaced != null) {
                    deleteTree(replaced);
                }
            } catch (Exception failure) {
                deleteTree(stage);
                if (replaced != null && Files.exists(replaced) && !Files.exists(target)) {
                    move(replaced, target);
                }
                if (failure instanceof MediaToolException mediaFailure) {
                    throw mediaFailure;
                }
                throw new MediaToolException("cannot install bundled media tools", failure);
            }
            return installation(target);
        } catch (MediaToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MediaToolException("cannot prepare bundled media tools", failure);
        }
    }

    private void requireWindowsX64() {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (!os.contains("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new MediaToolException("bundled media tools support Windows x64 only");
        }
    }

    private void installInto(Path stage) throws Exception {
        Path bin = Files.createDirectories(stage.resolve("bin"));
        copyVerifiedResource(manifest.ytDlpResource(), manifest.ytDlpSha256(), bin.resolve("yt-dlp.exe"));
        try (InputStream resource = openResource(manifest.ffmpegArchiveResource())) {
            extractFfmpeg(resource, stage.resolve("ffmpeg"));
        }
        bin.resolve("yt-dlp.exe").toFile().setExecutable(true, true);
        stage.resolve("ffmpeg/bin/ffmpeg.exe").toFile().setExecutable(true, true);
        stage.resolve("ffmpeg/bin/ffprobe.exe").toFile().setExecutable(true, true);
    }

    private void copyVerifiedResource(String resourceName, String expectedHash, Path destination) throws Exception {
        try (InputStream input = openResource(resourceName)) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        requireHash(destination, expectedHash);
    }

    private InputStream openResource(String name) {
        InputStream input = resources.getResourceAsStream(name);
        if (input == null) {
            throw new MediaToolException("missing bundled media resource: " + name);
        }
        return input;
    }

    private void extractFfmpeg(InputStream resource, Path destination) throws Exception {
        MessageDigest archiveDigest = MessageDigest.getInstance("SHA-256");
        Path archive = destination.getParent().resolve("ffmpeg.bundle.zip");
        Files.createDirectories(destination);
        try (var output = Files.newOutputStream(archive)) {
            resource.transferTo(new java.security.DigestOutputStream(output, archiveDigest));
        }
        String archiveHash = HexFormat.of().formatHex(archiveDigest.digest());
        if (!archiveHash.equals(manifest.ffmpegArchiveSha256())) {
            throw new MediaToolException("bundled FFmpeg archive checksum mismatch");
        }
        int entries = 0;
        long extractedBytes = 0;
        String requiredPrefix = manifest.ffmpegArchiveRoot() + "/";
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new MediaToolException("bundled FFmpeg archive contains too many entries");
                }
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(requiredPrefix) || name.contains("../") || name.startsWith("/")
                        || name.contains(":")) {
                    throw new MediaToolException("unsafe bundled FFmpeg archive entry");
                }
                String relativeName = name.substring(requiredPrefix.length());
                if (relativeName.isEmpty()) {
                    continue;
                }
                Path output = destination.resolve(relativeName).normalize();
                if (!output.startsWith(destination)) {
                    throw new MediaToolException("bundled FFmpeg archive escapes installation root");
                }
                if (entry.isDirectory() || !manifest.extractedSha256().containsKey(relativeName)) {
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (var sink = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        extractedBytes = Math.addExact(extractedBytes, read);
                        if (extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw new MediaToolException("bundled FFmpeg archive exceeds extraction limit");
                        }
                        sink.write(buffer, 0, read);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private boolean verify(Path target) {
        try {
            requireHash(target.resolve("bin/yt-dlp.exe"), manifest.ytDlpSha256());
            for (var entry : manifest.extractedSha256().entrySet()) {
                requireHash(target.resolve("ffmpeg").resolve(entry.getKey()), entry.getValue());
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static void requireHash(Path file, String expected) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new MediaToolException("missing bundled media tool file: " + file.getFileName());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw new MediaToolException("bundled media tool checksum mismatch: " + file.getFileName());
        }
    }

    private static MediaToolInstallation installation(Path root) {
        return new MediaToolInstallation(root, root.resolve("bin/yt-dlp.exe"),
                root.resolve("ffmpeg/bin/ffmpeg.exe"), root.resolve("ffmpeg/bin/ffprobe.exe"),
                root.resolve("ffmpeg/LICENSE.txt"));
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
