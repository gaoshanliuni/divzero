package dev.mineagent.runtime.worker.modindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class ModJarIndexer {
    private static final String NEOFORGE_METADATA = "META-INF/neoforge.mods.toml";
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern DISPLAY_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");
    private final int maximumEntries;
    private final int maximumMetadataBytes;

    public ModJarIndexer(int maximumEntries, int maximumMetadataBytes) {
        if (maximumEntries < 1 || maximumMetadataBytes < 1) {
            throw new IllegalArgumentException("invalid JAR index limits");
        }
        this.maximumEntries = maximumEntries;
        this.maximumMetadataBytes = maximumMetadataBytes;
    }

    public ModJarIndex index(Path jarPath) throws Exception {
        Path absolute = jarPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || !absolute.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("not a readable JAR");
        }
        var classes = new ArrayList<String>();
        var sources = new ArrayList<String>();
        String metadata = "";
        try (var jar = new JarFile(absolute.toFile(), false)) {
            int count = 0;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > maximumEntries) {
                    throw new IllegalArgumentException("JAR entry limit exceeded");
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("../") || name.contains("..\\")) {
                    throw new IllegalArgumentException("unsafe JAR entry path");
                }
                if (NEOFORGE_METADATA.equals(name)) {
                    try (var input = jar.getInputStream(entry)) {
                        byte[] bytes = input.readNBytes(maximumMetadataBytes + 1);
                        if (bytes.length > maximumMetadataBytes) {
                            throw new IllegalArgumentException("mod metadata exceeds limit");
                        }
                        metadata = new String(bytes, StandardCharsets.UTF_8);
                    }
                } else if (!entry.isDirectory() && name.endsWith(".class")
                        && !name.equals("module-info.class") && !name.startsWith("META-INF/versions/")) {
                    classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                } else if (!entry.isDirectory() && (name.startsWith("sources/") || name.startsWith("src/"))
                        && name.endsWith(".java")) {
                    sources.add(name);
                }
            }
        }
        if (metadata.isBlank()) {
            throw new IllegalArgumentException("NeoForge metadata is missing");
        }
        classes.sort(String::compareTo);
        sources.sort(String::compareTo);
        return new ModJarIndex(
                capture(MOD_ID, metadata, "modId"),
                capture(VERSION, metadata, "version"),
                capture(DISPLAY_NAME, metadata, "displayName"),
                sha256(absolute),
                Files.size(absolute),
                classes,
                sources
        );
    }

    private static String capture(Pattern pattern, String input, String field) {
        var matcher = pattern.matcher(input);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            throw new IllegalArgumentException("mod metadata is missing " + field);
        }
        return matcher.group(1);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
