package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.RuntimeDefinition;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import dev.mineagent.runtime.api.packages.RuntimePackageType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ParsedRuntimePackage(
        String name,
        String version,
        RuntimePackageType type,
        ActivationMode activationMode,
        Map<UUID, String> dependencies,
        Set<String> permissions,
        Map<String, RuntimeEntrypoint> entrypoints,
        List<RuntimeDefinition> definitions,
        List<GeneratedFile> files,
        dev.mineagent.runtime.api.packages.NativeCompatibility nativeCompatibility
) {
    public ParsedRuntimePackage(String name,String version,RuntimePackageType type,ActivationMode mode,Map<UUID,String> dependencies,Set<String> permissions,Map<String,RuntimeEntrypoint> entrypoints,List<RuntimeDefinition> definitions,List<GeneratedFile> files){this(name,version,type,mode,dependencies,permissions,entrypoints,definitions,files,null);}
    public ParsedRuntimePackage {
        dependencies = Map.copyOf(dependencies);
        permissions = Set.copyOf(permissions);
        entrypoints = Map.copyOf(entrypoints);
        definitions = List.copyOf(definitions);
        files = List.copyOf(files);
    }
}
