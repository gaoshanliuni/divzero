package dev.mineagent.runtime.worker.modindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class ModJarIndexerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesNeoForgeMetadataClassesAndBundledSourcesWithoutLoadingJar() throws Exception {
        Path jar = temporaryDirectory.resolve("example.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, "META-INF/neoforge.mods.toml", """
                    [[mods]]
                    modId="example_mod"
                    version="1.2.3"
                    displayName="Example"
                    """);
            write(output, "dev/example/Example.class", new byte[]{1, 2, 3});
            write(output, "sources/dev/example/Example.java", "package dev.example;");
        }

        var index = new ModJarIndexer(100, 1024 * 1024).index(jar);

        assertEquals("example_mod", index.modId());
        assertEquals("1.2.3", index.version());
        assertEquals(java.util.List.of("dev.example.Example"), index.classNames());
        assertEquals(java.util.List.of("sources/dev/example/Example.java"), index.sourceEntries());
        assertTrue(index.sha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsExcessiveEntryCountAndOversizedMetadata() throws Exception {
        Path jar = temporaryDirectory.resolve("large.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, "META-INF/neoforge.mods.toml", "x".repeat(100));
            write(output, "A.class", new byte[]{1});
        }

        assertThrows(IllegalArgumentException.class, () -> new ModJarIndexer(1, 1024).index(jar));
        assertThrows(IllegalArgumentException.class, () -> new ModJarIndexer(10, 10).index(jar));
    }

    private static void write(JarOutputStream output, String name, String text) throws Exception {
        write(output, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
