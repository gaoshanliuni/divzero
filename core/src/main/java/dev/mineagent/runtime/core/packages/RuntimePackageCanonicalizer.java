package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class RuntimePackageCanonicalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private RuntimePackageCanonicalizer() {
    }

    public static byte[] canonicalManifest(RuntimePackage runtimePackage) throws Exception {
        var manifest = new TreeMap<String, Object>();
        manifest.put("packageId", runtimePackage.packageId().toString());
        manifest.put("type", runtimePackage.type().name());
        manifest.put("name", runtimePackage.name());
        manifest.put("version", runtimePackage.version());
        manifest.put("activationMode", runtimePackage.activationMode().name());
        manifest.put("origin", runtimePackage.origin().name());
        if(runtimePackage.nativeCompatibility()!=null)manifest.put("nativeCompatibility",runtimePackage.nativeCompatibility().wire());
        manifest.put("dependencies", stringKeyed(runtimePackage.dependencies()));
        manifest.put("permissions", runtimePackage.permissions().stream().sorted().toList());
        var entrypoints = new TreeMap<String, Object>();
        runtimePackage.entrypoints().forEach((id, entrypoint) -> entrypoints.put(id, Map.of(
                "path", entrypoint.path(),
                "sha256", entrypoint.sha256(),
                "side", entrypoint.side().name())));
        manifest.put("entrypoints", entrypoints);
        var definitions = new TreeMap<String, Object>();
        runtimePackage.definitions().forEach((id, definition) -> definitions.put(id.toString(), Map.of(
                "definitionId", definition.definitionId().toString(),
                "name", definition.name(),
                "kind", definition.kind().name(),
                "entrypointId", definition.entrypointId(),
                "resourcePaths", definition.resourcePaths().stream().sorted().toList(),
                "settingsSchema", new TreeMap<>(definition.settingsSchema()),
                "revision", definition.revision(),
                "stateSchemaVersion", definition.stateSchemaVersion(),
                "migrationEntrypointId", definition.migrationEntrypointId() == null
                        ? "" : definition.migrationEntrypointId())));
        manifest.put("definitions", definitions);
        var resources = new TreeMap<String, Object>();
        runtimePackage.resources().forEach((path, resource) -> resources.put(path, Map.of(
                "path", resource.path(),
                "sha256", resource.sha256(),
                "side", resource.side().name(),
                "mediaType", resource.mediaType(),
                "size", resource.size())));
        manifest.put("resources", resources);
        return stableJson(manifest);
    }

    public static byte[] stableJson(Map<String, ?> value) throws Exception { return MAPPER.writeValueAsBytes(value); }

    public static String sha256(RuntimePackage runtimePackage) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalManifest(runtimePackage)));
    }

    public static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static String sha256(String value) throws Exception {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var ordered = new TreeMap<String, Object>();
        source.forEach((key, value) -> ordered.put(key.toString(), value));
        return ordered;
    }
}
