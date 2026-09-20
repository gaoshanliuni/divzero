package dev.mineagent.runtime.client.webui;

import dev.mineagent.runtime.api.packages.RuntimeResourceRef;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves an already-authorized manifest, never arbitrary filesystem paths or server scripts. */
public final class PackageUiResolver {
    private static final Set<String> TYPES = Set.of("text/html", "text/css", "text/javascript",
            "application/javascript", "application/json", "image/png", "image/jpeg", "image/webp",
            "image/gif", "image/svg+xml", "font/woff", "font/woff2");
    private PackageUiResolver() {}

    @FunctionalInterface public interface ContentReader { byte[] read(String hash) throws IOException; }

    public record Asset(byte[] bytes, String mediaType) {
        public Asset {
            if (bytes == null || !TYPES.contains(mediaType)) throw new IllegalArgumentException("UI_RESOURCE_TYPE");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        public int size() { return bytes.length; }
    }

    public static Map<String, Asset> resolve(Map<String, RuntimeResourceRef> resources, ContentReader reader,
                                              long maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
        Map<String, Asset> result = new LinkedHashMap<>();
        long total = 0;
        for (var entry : resources.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith("ui/")) continue;
            requirePath(path);
            RuntimeResourceRef ref = entry.getValue();
            if (!path.equals(ref.path()) || ref.side() == RuntimeResourceSide.SERVER)
                throw new IllegalArgumentException("UI_RESOURCE_OWNERSHIP");
            if (ref.size() > maxBytes - total) throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
            byte[] bytes = reader.read(ref.sha256());
            if (bytes.length != ref.size() || !sha256(bytes).equals(ref.sha256()))
                throw new IllegalArgumentException("UI_RESOURCE_INTEGRITY");
            total += bytes.length;
            result.put(path, new Asset(bytes, ref.mediaType()));
        }
        return Map.copyOf(result);
    }

    public static void requirePath(String path) {
        if (path == null || path.length() > 256 || !path.matches("[A-Za-z0-9_@.-]+(?:/[A-Za-z0-9_@.-]+)*"))
            throw new IllegalArgumentException("UI_RESOURCE_PATH");
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("UI_RESOURCE_PATH");
        }
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
