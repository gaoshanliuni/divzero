package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.RuntimeDefinition;
import dev.mineagent.runtime.api.packages.RuntimeDefinitionKind;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import dev.mineagent.runtime.api.packages.RuntimePackageType;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.scripting.preflight.ScriptPreflight;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RuntimePackageOutputParser {
    private static final int MAX_FILES = 256;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private static final Set<String> ROOT_FIELDS = Set.of("manifest", "files");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "name", "version", "type", "activationMode", "permissions", "entrypoints",
            "definitions", "dependencies", "nativeCompatibility");
    private static final Set<String> FILE_FIELDS = Set.of(
            "path", "side", "mediaType", "encoding", "content", "sha256");
    private static final Set<String> ENTRYPOINT_FIELDS = Set.of("path", "side", "sha256");
    private static final Set<String> DEFINITION_FIELDS = Set.of(
            "definitionId", "name", "kind", "entrypointId", "resourcePaths", "settingsSchema",
            "revision", "stateSchemaVersion", "migrationEntrypointId");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScriptPreflight preflight = new ScriptPreflight();
    private final dev.mineagent.runtime.scripting.preflight.BrowserScriptPreflight browserPreflight =
            new dev.mineagent.runtime.scripting.preflight.BrowserScriptPreflight();

    public ParsedRuntimePackage parse(String output) {
        try {
            if (output == null || output.isBlank() || output.length() > 24 * 1024 * 1024) {
                throw invalid("PACKAGE_OUTPUT_INVALID", "empty or excessive package output");
            }
            JsonNode parsed = mapper.readTree(output);
            ObjectNode root = object(parsed, "root");
            requireOnly(root, ROOT_FIELDS, "root");
            ObjectNode manifest = object(root.get("manifest"), "manifest");
            requireOnly(manifest, MANIFEST_FIELDS, "manifest");
            String name = text(manifest, "name", 128);
            String version = text(manifest, "version", 64);
            RuntimePackageType type = enumValue(RuntimePackageType.class, text(manifest, "type", 32), "type");
            ActivationMode activationMode = enumValue(
                    ActivationMode.class, text(manifest, "activationMode", 32), "activationMode");
            Set<String> permissions = stringSet(manifest.get("permissions"), 128, 128, "permissions");
            Map<UUID, String> dependencies = dependencies(manifest.get("dependencies"));
            Map<String, RuntimeEntrypoint> entrypoints = entrypoints(manifest.get("entrypoints"));
            List<GeneratedFile> files = files(root.get("files"));
            Map<String, GeneratedFile> filesByPath = new LinkedHashMap<>();
            for (GeneratedFile file : files) {
                if (filesByPath.put(file.path(), file) != null) {
                    throw invalid("DUPLICATE_FILE", "duplicate generated file " + file.path());
                }
            }
            entrypoints.forEach((id, entrypoint) -> {
                GeneratedFile file = filesByPath.get(entrypoint.path());
                if (file == null || file.side() != entrypoint.side()
                        || !file.sha256().equals(entrypoint.sha256())) {
                    throw invalid("ENTRYPOINT_FILE_MISMATCH", "entrypoint file mismatch: " + id);
                }
            });
            List<RuntimeDefinition> definitions = definitions(
                    manifest.get("definitions"), entrypoints, filesByPath.keySet());
            var settings=filesByPath.get(dev.mineagent.runtime.core.ui.UiViewSettings.PATH);
            if(settings!=null){
                if(settings.side()!=RuntimeResourceSide.CLIENT||!settings.mediaType().equals("application/json"))throw invalid("UI_VIEW_SETTINGS_RESOURCE","View settings must be a CLIENT JSON resource");
                var htmlEntries=entrypoints.values().stream().filter(e->e.side()==RuntimeResourceSide.CLIENT&&e.path().endsWith(".html")).map(e->e.path()).collect(java.util.stream.Collectors.toSet());
                dev.mineagent.runtime.core.ui.UiViewSettings.parse(new String(settings.content(),StandardCharsets.UTF_8),htmlEntries);
            }
            var result=new ParsedRuntimePackage(name, version, type, activationMode, dependencies,
                    permissions, entrypoints, definitions, files,nativeCompatibility(manifest.get("nativeCompatibility")));
            FeedbackPackageContract.validate(result);return result;
        } catch (PackageOutputException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new PackageOutputException("PACKAGE_OUTPUT_INVALID",
                    invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage());
        }
    }

    private dev.mineagent.runtime.api.packages.NativeCompatibility nativeCompatibility(JsonNode node)throws Exception{
        if(node==null||node.isNull())return null;
        var root=object(node,"nativeCompatibility");requireOnly(root,Set.of("schema","targets"),"nativeCompatibility");
        if(!root.path("schema").isInt()||root.path("schema").intValue()!=1)throw invalid("NATIVE_COMPATIBILITY_INVALID","schema must be 1");
        var targets=object(root.get("targets"),"nativeCompatibility.targets");requireOnly(targets,Set.of("SERVER","CLIENT"),"nativeCompatibility.targets");
        for(var e:targets.properties()){
            var target=object(e.getValue(),"native target");requireOnly(target,Set.of("minecraft","loader","loaderVersion","namespace","javaFeature","requiredMods"),"native target");
            for(String key:List.of("minecraft","loader","loaderVersion","namespace"))text(target,key,128);
            if(!target.path("javaFeature").isInt())throw invalid("NATIVE_COMPATIBILITY_INVALID","javaFeature must be an integer");
            var mods=object(target.get("requiredMods"),"requiredMods");for(var mod:mods.properties())if(!mod.getValue().isTextual())throw invalid("NATIVE_COMPATIBILITY_INVALID","exact Mod version required");
        }
        try{return mapper.treeToValue(root,dev.mineagent.runtime.api.packages.NativeCompatibility.class);}catch(Exception invalid){throw invalid("NATIVE_COMPATIBILITY_INVALID","invalid native compatibility contract");}
    }

    private List<GeneratedFile> files(JsonNode node) throws Exception {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_FILES) {
            throw invalid("FILE_LIMIT_EXCEEDED", "invalid generated files array");
        }
        var files = new ArrayList<GeneratedFile>();
        long total = 0;
        for (JsonNode value : node) {
            ObjectNode file = object(value, "file");
            requireOnly(file, FILE_FIELDS, "file");
            String path = packagePath(text(file, "path", 256));
            RuntimeResourceSide side = enumValue(
                    RuntimeResourceSide.class, text(file, "side", 16), "file side");
            if (path.startsWith("ui/") && side == RuntimeResourceSide.SERVER)
                throw invalid("UI_SERVER_RESOURCE", "Browser resources cannot be server scripts: " + path);
            String mediaType = text(file, "mediaType", 128);
            String encoding = text(file, "encoding", 16);
            String encodedContent = requiredText(file, "content");
            byte[] content = switch (encoding) {
                case "utf8" -> encodedContent.getBytes(StandardCharsets.UTF_8);
                case "base64" -> Base64.getDecoder().decode(encodedContent);
                default -> throw invalid("ENCODING_UNSUPPORTED", "unsupported generated file encoding");
            };
            total += content.length;
            if (content.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                throw invalid("FILE_LIMIT_EXCEEDED", "generated file payload exceeds size limit");
            }
            String expectedHash = text(file, "sha256", 64);
            String actualHash = RuntimePackageCanonicalizer.sha256(content);
            if (!actualHash.equals(expectedHash)) {
                throw invalid("HASH_MISMATCH", "generated file hash mismatch: " + path);
            }
            if (path.endsWith(".json") || mediaType.equals("application/json")) {
                mapper.readTree(content);
            }
            if (path.endsWith(".js") || path.endsWith(".mjs") || mediaType.equals("application/javascript") || mediaType.equals("text/javascript")) {
                var inspected = path.startsWith("ui/") ? browserPreflight.inspect(new String(content, StandardCharsets.UTF_8))
                        : preflight.inspect(new String(content, StandardCharsets.UTF_8));
                if (!inspected.accepted()) {
                    throw invalid("PREFLIGHT_REJECTED", inspected.diagnostics().toString());
                }
            }
            files.add(new GeneratedFile(path, side, mediaType, actualHash, content));
        }
        return List.copyOf(files);
    }

    private Map<String, RuntimeEntrypoint> entrypoints(JsonNode node) {
        ObjectNode object = object(node, "entrypoints");
        if (object.isEmpty() || object.size() > 64) {
            throw invalid("ENTRYPOINT_INVALID", "entrypoints must be non-empty and bounded");
        }
        var result = new LinkedHashMap<String, RuntimeEntrypoint>();
        object.properties().forEach(entry -> {
            if (!entry.getKey().matches("[A-Za-z0-9_.-]{1,64}")) {
                throw invalid("ENTRYPOINT_INVALID", "invalid entrypoint id");
            }
            ObjectNode value = object(entry.getValue(), "entrypoint");
            requireOnly(value, ENTRYPOINT_FIELDS, "entrypoint");
            result.put(entry.getKey(), new RuntimeEntrypoint(
                    packagePath(text(value, "path", 256)),
                    enumValue(RuntimeResourceSide.class, text(value, "side", 16), "entrypoint side"),
                    text(value, "sha256", 64)));
        });
        return Map.copyOf(result);
    }

    private List<RuntimeDefinition> definitions(
            JsonNode node,
            Map<String, RuntimeEntrypoint> entrypoints,
            Set<String> filePaths
    ) {
        if (node == null || !node.isArray() || node.size() > 128) {
            throw invalid("DEFINITION_INVALID", "invalid definitions array");
        }
        var result = new ArrayList<RuntimeDefinition>();
        var ids = new HashSet<UUID>();
        for (JsonNode value : node) {
            ObjectNode definition = object(value, "definition");
            requireOnly(definition, DEFINITION_FIELDS, "definition");
            UUID id = UUID.fromString(text(definition, "definitionId", 36));
            if (!ids.add(id)) {
                throw invalid("DEFINITION_INVALID", "duplicate definition id");
            }
            String entrypointId = text(definition, "entrypointId", 64);
            if (!entrypoints.containsKey(entrypointId)) {
                throw invalid("DEFINITION_INVALID", "unknown definition entrypoint");
            }
            Set<String> resourcePaths = stringSet(
                    definition.get("resourcePaths"), 256, 256, "resourcePaths");
            if (!filePaths.containsAll(resourcePaths)) {
                throw invalid("DEFINITION_INVALID", "definition references unknown file");
            }
            Map<String, String> settings = stringMap(definition.get("settingsSchema"), 128, "settingsSchema");
            long revision = positiveLong(definition, "revision");
            int schemaVersion = Math.toIntExact(positiveLong(definition, "stateSchemaVersion"));
            String migration = definition.has("migrationEntrypointId")
                    ? text(definition, "migrationEntrypointId", 64) : null;
            if (migration != null && !entrypoints.containsKey(migration)) {
                throw invalid("DEFINITION_INVALID", "unknown migration entrypoint");
            }
            result.add(new RuntimeDefinition(id, text(definition, "name", 128),
                    enumValue(RuntimeDefinitionKind.class, text(definition, "kind", 32), "definition kind"),
                    entrypointId, resourcePaths, settings, revision, schemaVersion, migration));
        }
        return List.copyOf(result);
    }

    private Map<UUID, String> dependencies(JsonNode node) {
        ObjectNode object = object(node, "dependencies");
        if (object.size() > 128) {
            throw invalid("DEPENDENCY_INVALID", "too many dependencies");
        }
        var result = new LinkedHashMap<UUID, String>();
        object.properties().forEach(entry -> result.put(
                UUID.fromString(entry.getKey()), scalarText(entry.getValue(), 64, "dependency version")));
        return Map.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode node, int limit, String context) {
        ObjectNode object = object(node, context);
        if (object.size() > limit) {
            throw invalid("PACKAGE_OUTPUT_INVALID", context + " exceeds limit");
        }
        var result = new LinkedHashMap<String, String>();
        object.properties().forEach(entry -> {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128) {
                throw invalid("PACKAGE_OUTPUT_INVALID", "invalid " + context + " key");
            }
            result.put(entry.getKey(), scalarText(entry.getValue(), 2048, context));
        });
        return Map.copyOf(result);
    }

    private static Set<String> stringSet(JsonNode node, int limit, int maxLength, String context) {
        if (node == null || !node.isArray() || node.size() > limit) {
            throw invalid("PACKAGE_OUTPUT_INVALID", "invalid " + context);
        }
        var result = new LinkedHashSet<String>();
        for (JsonNode value : node) {
            String text = scalarText(value, maxLength, context);
            if (!result.add(text)) {
                throw invalid("PACKAGE_OUTPUT_INVALID", "duplicate " + context + " value");
            }
        }
        return Set.copyOf(result);
    }

    private static long positiveLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 1) {
            throw invalid("PACKAGE_OUTPUT_INVALID", "invalid " + field);
        }
        return value.longValue();
    }

    private static String text(ObjectNode node, String field, int maximumLength) {
        String value = scalarText(node.get(field), maximumLength, field);
        if (value.isBlank()) {
            throw invalid("PACKAGE_OUTPUT_INVALID", "blank " + field);
        }
        return value;
    }

    private static String requiredText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("PACKAGE_OUTPUT_INVALID", "missing " + field);
        }
        return value.textValue();
    }

    private static String scalarText(JsonNode value, int maximumLength, String context) {
        if (value == null || !value.isTextual() || value.textValue().length() > maximumLength) {
            throw invalid("PACKAGE_OUTPUT_INVALID", "invalid " + context);
        }
        return value.textValue();
    }

    private static String packagePath(String path) {
        try {
            new dev.mineagent.runtime.api.packages.RuntimeResourceRef(
                    path, "0".repeat(64), RuntimeResourceSide.COMMON, "application/octet-stream", 0);
            return path;
        } catch (IllegalArgumentException invalid) {
            throw new PackageOutputException("PATH_INVALID", invalid.getMessage());
        }
    }

    private static ObjectNode object(JsonNode node, String context) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid("PACKAGE_OUTPUT_INVALID", context + " must be an object");
        }
        return object;
    }

    private static void requireOnly(ObjectNode object, Set<String> allowed, String context) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw invalid("PACKAGE_OUTPUT_INVALID", "unknown " + context + " field: " + field);
            }
        });
        for (String field : allowed) {
            if (!field.equals("migrationEntrypointId") && !object.has(field)) {
                throw invalid("PACKAGE_OUTPUT_INVALID", "missing " + context + " field: " + field);
            }
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String context) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new PackageOutputException("PACKAGE_OUTPUT_INVALID", "invalid " + context);
        }
    }

    private static PackageOutputException invalid(String code, String message) {
        return new PackageOutputException(code, message);
    }
}
