package dev.mineagent.runtime.worker.modindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.util.Objects;
import dev.mineagent.runtime.api.packages.ResourceIds;
import java.util.ArrayList;
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
        if (!Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute) || !absolute.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("not a readable JAR");
        }
        var before=Files.readAttributes(absolute,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
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
        var after=Files.readAttributes(absolute,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!Objects.equals(before.fileKey(),after.fileKey())||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("MOD_INDEX_SOURCE_CHANGED");
        classes.sort(String::compareTo);
        sources.sort(String::compareTo);
        return new ModJarIndex(
                capture(MOD_ID, metadata, "modId"),
                capture(VERSION, metadata, "version"),
                capture(DISPLAY_NAME, metadata, "displayName"),
                ResourceIds.create(),
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

}
