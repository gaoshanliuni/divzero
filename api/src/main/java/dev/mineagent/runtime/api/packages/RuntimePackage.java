package dev.mineagent.runtime.api.packages;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RuntimePackage(
        UUID packageId,
        RuntimePackageType type,
        String name,
        String version,
        ActivationMode activationMode,
        Map<UUID, String> dependencies,
        Set<String> permissions,
        Map<String, RuntimeEntrypoint> entrypoints,
        Map<UUID, RuntimeDefinition> definitions,
        Map<String, RuntimeResourceRef> resources,
        PackageOrigin origin,
        boolean enabled,
        long revision,
        String canonicalSha256,
        String signature,
        long updatedAtEpochMillis,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        NativeCompatibility nativeCompatibility
) {
    public RuntimePackage(UUID packageId,RuntimePackageType type,String name,String version,ActivationMode activationMode,Map<UUID,String> dependencies,Set<String> permissions,Map<String,RuntimeEntrypoint> entrypoints,Map<UUID,RuntimeDefinition> definitions,Map<String,RuntimeResourceRef> resources,PackageOrigin origin,boolean enabled,long revision,String canonicalSha256,String signature,long updatedAtEpochMillis){this(packageId,type,name,version,activationMode,dependencies,permissions,entrypoints,definitions,resources,origin,enabled,revision,canonicalSha256,signature,updatedAtEpochMillis,null);}
    public RuntimePackage {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(type, "type");
        if (name == null || name.isBlank() || name.length() > 128
                || version == null || !version.matches("[0-9A-Za-z_.+-]{1,64}")) {
            throw new IllegalArgumentException("invalid package identity");
        }
        Objects.requireNonNull(activationMode, "activationMode");
        dependencies = Map.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        entrypoints = Map.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        definitions = Map.copyOf(Objects.requireNonNull(definitions, "definitions"));
        resources = Map.copyOf(Objects.requireNonNull(resources, "resources"));
        Objects.requireNonNull(origin, "origin");
        if (revision < 1 || canonicalSha256 == null || !canonicalSha256.matches("[0-9a-f]{64}")
                || signature == null) {
            throw new IllegalArgumentException("invalid package revision or signature metadata");
        }
        Map<String, RuntimeEntrypoint> copiedEntrypoints = entrypoints;
        Map<String, RuntimeResourceRef> copiedResources = resources;
        entrypoints.forEach((id, entrypoint) -> {
            if (id == null || id.isBlank() || id.length() > 64 || entrypoint == null) {
                throw new IllegalArgumentException("invalid entrypoint mapping");
            }
        });
        resources.forEach((path, resource) -> {
            if (resource == null || !path.equals(resource.path())) {
                throw new IllegalArgumentException("resource map key mismatch");
            }
        });
        entrypoints.forEach((id, entrypoint) -> {
            RuntimeResourceRef owned = copiedResources.get(entrypoint.path());
            if (owned == null || !owned.sha256().equals(entrypoint.sha256()) || owned.side() != entrypoint.side()) {
                throw new IllegalArgumentException("entrypoint is not backed by an owned resource");
            }
        });
        definitions.forEach((id, definition) -> {
            if (definition == null || !id.equals(definition.definitionId())) {
                throw new IllegalArgumentException("definition map key mismatch");
            }
            if (!copiedEntrypoints.containsKey(definition.entrypointId())) {
                throw new IllegalArgumentException("definition references unknown entrypoint");
            }
            if (definition.migrationEntrypointId() != null
                    && !copiedEntrypoints.containsKey(definition.migrationEntrypointId())) {
                throw new IllegalArgumentException("definition references unknown migration entrypoint");
            }
            if (!copiedResources.keySet().containsAll(definition.resourcePaths())) {
                throw new IllegalArgumentException("definition references unknown resource");
            }
        });
    }
}
