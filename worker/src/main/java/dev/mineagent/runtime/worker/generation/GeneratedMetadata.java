package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;

/** The trusted build stage computes metadata; the model is not asked to guess SHA-256 strings. */
public final class GeneratedMetadata {
    private GeneratedMetadata() {}
    public static String complete(String source) {
        try {
            if (source == null || source.length() > 24 * 1024 * 1024) throw new IllegalArgumentException("OUTPUT_LIMIT");
            var json = new ObjectMapper();
            var root = json.readTree(source);
            var files = root.path("files");
            if (!files.isArray() || files.size() > 256) throw new IllegalArgumentException("FILES");
            var hashes = new HashMap<String, String>();
            long total = 0;
            for (var raw : files) {
                if (!(raw instanceof ObjectNode file) || !file.path("content").isTextual()) throw new IllegalArgumentException("FILE");
                byte[] content = switch (file.path("encoding").asText()) {
                    case "utf8" -> file.path("content").asText().getBytes(StandardCharsets.UTF_8);
                    case "base64" -> Base64.getDecoder().decode(file.path("content").asText());
                    default -> throw new IllegalArgumentException("ENCODING");
                };
                total += content.length;
                if (content.length > 4 * 1024 * 1024 || total > 16 * 1024 * 1024) throw new IllegalArgumentException("BYTE_BUDGET");
                String actual = RuntimePackageCanonicalizer.sha256(content);
                hashes.put(file.path("path").asText(), actual);
                if (!file.has("sha256")) file.put("sha256", actual);
            }
            var entries = root.path("manifest").path("entrypoints");
            if (!entries.isObject()) throw new IllegalArgumentException("ENTRYPOINTS");
            for (var entry : entries.properties()) {
                if (!(entry.getValue() instanceof ObjectNode value)) throw new IllegalArgumentException("ENTRYPOINT");
                if (!value.has("sha256") && hashes.containsKey(value.path("path").asText()))
                    value.put("sha256", hashes.get(value.path("path").asText()));
            }
            return json.writeValueAsString(root);
        } catch (Exception failure) { throw new PackageOutputException("PACKAGE_METADATA_INVALID", failure.getMessage()); }
    }
}
