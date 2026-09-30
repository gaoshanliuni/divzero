package dev.mineagent.runtime.worker.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledMediaToolsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyExtractsAndRepairsVerifiedWindowsBundle() throws Exception {
        Path resources = temporaryDirectory.resolve("resources");
        byte[] ytDlp = "fake-yt-dlp".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ffmpeg = "fake-ffmpeg".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ffprobe = "fake-ffprobe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] license = "LGPL-2.1-or-later".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.createDirectories(resources.resolve("bundle"));
        Files.write(resources.resolve("bundle/yt-dlp.exe"), ytDlp);
        Path archive = resources.resolve("bundle/ffmpeg.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "fake-ffmpeg/bin/ffmpeg.exe", ffmpeg);
            entry(zip, "fake-ffmpeg/bin/ffprobe.exe", ffprobe);
            entry(zip, "fake-ffmpeg/LICENSE.txt", license);
            entry(zip, "fake-ffmpeg/doc/unneeded.html", "large documentation".getBytes());
        }
        var manifest = new MediaToolBundleManifest(
                "test-bundle", "bundle/yt-dlp.exe", sha256(ytDlp),
                "bundle/ffmpeg.zip", sha256(Files.readAllBytes(archive)), "fake-ffmpeg",
                Map.of("bin/ffmpeg.exe", sha256(ffmpeg), "bin/ffprobe.exe", sha256(ffprobe),
                        "LICENSE.txt", sha256(license)));

        try (var loader = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()})) {
            var tools = new BundledMediaTools(temporaryDirectory.resolve("installed"), loader, manifest,
                    "Windows 11", "amd64");
            MediaToolInstallation first = tools.ensureInstalled();
            assertEquals("fake-yt-dlp", Files.readString(first.ytDlp()));
            assertEquals("fake-ffmpeg", Files.readString(first.ffmpeg()));
            assertEquals("fake-ffprobe", Files.readString(first.ffprobe()));
            assertTrue(Files.isRegularFile(first.license()));
            assertTrue(Files.notExists(first.root().resolve("ffmpeg/doc/unneeded.html")));

            Files.writeString(first.ffmpeg(), "tampered");
            MediaToolInstallation repaired = tools.ensureInstalled();
            assertEquals("fake-ffmpeg", Files.readString(repaired.ffmpeg()));
        }
    }

    @Test
    void rejectsUnsupportedPlatformBeforeExtraction() {
        var manifest = new MediaToolBundleManifest(
                "test-bundle", "missing", "0".repeat(64), "missing", "0".repeat(64), "root",
                Map.of("bin/ffmpeg.exe", "0".repeat(64), "bin/ffprobe.exe", "0".repeat(64),
                        "LICENSE.txt", "0".repeat(64)));
        var tools = new BundledMediaTools(temporaryDirectory.resolve("installed"), getClass().getClassLoader(),
                manifest, "Linux", "amd64");

        assertThrows(MediaToolException.class, tools::ensureInstalled);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void productionBundleContainsPinnedRunnableTools() {
        org.junit.jupiter.api.Assumptions.assumeTrue(getClass().getResource("/META-INF/mineagent/tools/ffmpeg-n8.1.3-6-gff48edd8b2-win64-lgpl-shared-8.1.zip")!=null,"This standard build intentionally omits optional media binaries; with-media CI must run this check.");
        MediaToolInstallation installation = BundledMediaTools.windowsX64(
                temporaryDirectory.resolve("production-tools")).ensureInstalled();

        ToolResult ytDlp = new ExternalToolRunner(java.util.List.of(installation.ytDlp().toString()),
                Duration.ofSeconds(15), 64 * 1024).run(java.util.List.of("--version"));
        ToolResult ffmpeg = new ExternalToolRunner(java.util.List.of(installation.ffmpeg().toString()),
                Duration.ofSeconds(15), 256 * 1024).run(java.util.List.of("-version"));
        assertEquals(0, ytDlp.exitCode());
        assertEquals("2026.08.19", ytDlp.stdout().strip());
        assertEquals(0, ffmpeg.exitCode());
        assertTrue(ffmpeg.stdout().contains("ffmpeg version n8.1.3-6-gff48edd8b2"));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
