package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;

/** Explicit world-code/resource repair. UI-only edits keep their separate, unchanged policy. */
public final class WorldPatchPolicy {
    private WorldPatchPolicy(){}
    public static void requireBase(RuntimePackage base){
        if(base!=null&&base.activationMode()==ActivationMode.BOOT_EXTENSION){dev.mineagent.runtime.core.boot.BootExtensionPlan.validate(base);return;}
        if(base==null||base.entrypoints().get("server")==null||base.entrypoints().get("server").side()!=RuntimeResourceSide.SERVER||base.definitions().isEmpty()||!base.permissions().contains("RUN_CODE"))throw new IllegalArgumentException("WORLD_PATCH_BASE");
    }
    public static void require(RuntimePackage base,RuntimePackage candidate){
        requireBase(base);if(candidate!=null&&base.activationMode()==ActivationMode.BOOT_EXTENSION)dev.mineagent.runtime.core.boot.BootExtensionPlan.validate(candidate);
        if(candidate==null||!base.packageId().equals(candidate.packageId())||base.type()!=candidate.type()||!base.name().equals(candidate.name())||base.activationMode()!=ActivationMode.BOOT_EXTENSION&&!base.version().equals(candidate.version())
                ||base.activationMode()!=candidate.activationMode()||base.activationMode()!=ActivationMode.BOOT_EXTENSION&&!base.dependencies().equals(candidate.dependencies())||!java.util.Objects.equals(base.nativeCompatibility(),candidate.nativeCompatibility())||!base.permissions().equals(candidate.permissions())||!base.definitions().equals(candidate.definitions())
                ||base.origin()!=candidate.origin()||base.enabled()!=candidate.enabled()||candidate.revision()!=base.revision()+1||!base.entrypoints().keySet().equals(candidate.entrypoints().keySet()))throw new IllegalArgumentException("WORLD_PATCH_SCOPE");
        for(var entry:base.entrypoints().entrySet()){
            var old=entry.getValue();var next=candidate.entrypoints().get(entry.getKey());
            if(!old.path().equals(next.path())||old.side()!=next.side()||(UiPatchPolicy.ui(old.path())&&!old.equals(next)))throw new IllegalArgumentException("WORLD_PATCH_ENTRYPOINT");
        }
        for(var old:base.resources().values()){
            var next=candidate.resources().get(old.path());
            if(UiPatchPolicy.ui(old.path())&&!old.equals(next)||next!=null&&old.side()!=next.side())throw new IllegalArgumentException("WORLD_PATCH_RESOURCE_SIDE");
        }
        for(var resource:candidate.resources().values())if(UiPatchPolicy.ui(resource.path())&&!resource.equals(base.resources().get(resource.path())))throw new IllegalArgumentException("WORLD_PATCH_UI_RESOURCE");
        for(var definition:candidate.definitions().values())if(!candidate.resources().keySet().containsAll(definition.resourcePaths()))throw new IllegalArgumentException("WORLD_PATCH_DECLARED_RESOURCE");
        if(base.canonicalSha256().equals(candidate.canonicalSha256()))throw new IllegalArgumentException("WORLD_PATCH_NO_CHANGE");
    }
}
