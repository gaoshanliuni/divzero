package dev.mineagent.runtime.core.boot;

import java.util.*;
import java.nio.file.Path;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;

/** Native-attested predecessor, not a page-supplied classpath exclusion. Empty moduleHash means not loaded in this JVM. */
public record BootReplacement(UUID build,UUID packageId,String canonical,String artifact,String modId,String moduleHash) {
    public BootReplacement {
        Objects.requireNonNull(build);Objects.requireNonNull(packageId);
        if(canonical==null||!canonical.matches("[a-f0-9]{64}")||artifact==null||!artifact.matches("[a-f0-9]{64}")||modId==null||!modId.matches("[a-z][a-z0-9_]{1,63}")||moduleHash==null||!moduleHash.matches("(?:[a-f0-9]{64})?"))throw new IllegalArgumentException("BOOT_REPLACEMENT_IDENTITY");
    }
    public void requirePlan(BootExtensionPlan plan){if(!packageId.equals(plan.manifest().packageId())||!modId.equals(plan.modId())||canonical.equals(plan.manifest().canonicalSha256()))throw new IllegalStateException("BOOT_REPLACEMENT_SOURCE");}
    public List<Path> compilerPaths(NativeCompilationSnapshot snapshot,List<Path> paths){
        if(paths.size()!=snapshot.modules().size())throw new IllegalStateException("BOOT_REPLACEMENT_CLASSPATH");
        boolean loaded=snapshot.environment().mods().containsKey(modId);
        if(moduleHash.isEmpty()){if(loaded)throw new IllegalStateException("BOOT_REPLACEMENT_MODULE_CHANGED");return paths;}
        if(!loaded||!snapshot.module(modId).sha256().equals(moduleHash))throw new IllegalStateException("BOOT_REPLACEMENT_MODULE_CHANGED");
        var selected=new ArrayList<Path>();int excluded=0;for(int i=0;i<paths.size();i++){if(snapshot.modules().get(i).name().equals(modId))excluded++;else selected.add(paths.get(i));}
        if(excluded!=1)throw new IllegalStateException("BOOT_REPLACEMENT_CLASSPATH");return List.copyOf(selected);
    }
}
