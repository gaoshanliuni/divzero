package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.file.Files;
import java.util.*;

/** Bounded transfer representation, not a second package format or an executable installer. */
public record PackagePreviewBundle(RuntimePackage manifest, String entry, Map<String, String> files) {
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    public static final int MAX_UI_BYTES = 8 * 1024 * 1024;
    public static final int CHUNK_BYTES = 16 * 1024;
    public PackagePreviewBundle { files = Map.copyOf(files); }
    public static PackagePreviewBundle decode(byte[] body) throws java.io.IOException {
        if (body.length > MAX_BYTES) throw new IllegalArgumentException("UI_BUNDLE_BUDGET");
        return new ObjectMapper().readValue(body, PackagePreviewBundle.class);
    }
    public static byte[] encode(RuntimePackage pkg, String entry, ContentAddressedStore store) throws Exception {
        if (!entry.startsWith("ui/") || !entry.endsWith(".html") || pkg.entrypoints().values().stream()
                .noneMatch(e -> e.path().equals(entry) && e.side() != RuntimeResourceSide.SERVER))
            throw new IllegalArgumentException("UI_ENTRYPOINT");
        long total = 0;
        var files = new LinkedHashMap<String, String>();
        if (pkg.resources().size() > 256) throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
        for (var ref : pkg.resources().values()) {
            if (!ref.path().startsWith("ui/")) continue;
            if (ref.side() == RuntimeResourceSide.SERVER) throw new IllegalArgumentException("UI_RESOURCE_OWNERSHIP");
            if (ref.size() < 0 || ref.size() > MAX_UI_BYTES - total || Files.size(store.pathFor(ref.sha256())) != ref.size())
                throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
            total += ref.size();
            byte[] bytes = store.read(ref.sha256());
            if (bytes.length != ref.size()) throw new IllegalArgumentException("UI_RESOURCE_SIZE");
            files.put(ref.sha256(), Base64.getEncoder().encodeToString(bytes));
        }
        byte[] body = new ObjectMapper().writeValueAsBytes(new PackagePreviewBundle(pkg, entry, files));
        if (body.length > MAX_BYTES) throw new IllegalArgumentException("UI_BUNDLE_BUDGET");
        return body;
    }
}
