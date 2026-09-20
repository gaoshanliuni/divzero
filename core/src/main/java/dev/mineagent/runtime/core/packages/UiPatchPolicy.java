package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
import java.util.*;

/** Sparse UI edits cannot smuggle gameplay/native-entry or permission/identity changes into an existing package. */
public final class UiPatchPolicy {
    private UiPatchPolicy(){}
    public static void require(RuntimePackage base,RuntimePackage candidate){
        if(candidate==null||!base.packageId().equals(candidate.packageId())||base.type()!=candidate.type()||!base.name().equals(candidate.name())
                ||!base.version().equals(candidate.version())||base.activationMode()!=candidate.activationMode()||!base.dependencies().equals(candidate.dependencies())
                ||!Objects.equals(base.nativeCompatibility(),candidate.nativeCompatibility())||!base.permissions().equals(candidate.permissions())||!base.definitions().equals(candidate.definitions())||base.origin()!=candidate.origin()
                ||base.enabled()!=candidate.enabled()||candidate.revision()!=base.revision()+1||!base.entrypoints().keySet().equals(candidate.entrypoints().keySet()))throw new IllegalArgumentException("UI_PATCH_SCOPE");
        for(var e:base.entrypoints().entrySet()){
            var next=candidate.entrypoints().get(e.getKey());var old=e.getValue();
            if(!old.path().equals(next.path())||old.side()!=next.side()||(!ui(old.path())&&!old.equals(next)))throw new IllegalArgumentException("UI_PATCH_ENTRYPOINT");
        }
        for(var e:base.resources().entrySet())if(!ui(e.getKey())&&!e.getValue().equals(candidate.resources().get(e.getKey())))throw new IllegalArgumentException("UI_PATCH_GAMEPLAY_RESOURCE");
        for(var e:candidate.resources().entrySet()){
            var old=base.resources().get(e.getKey());
            if(!ui(e.getKey())&&!e.getValue().equals(old))throw new IllegalArgumentException("UI_PATCH_GAMEPLAY_RESOURCE");
            if(ui(e.getKey())&&(e.getValue().side()==RuntimeResourceSide.SERVER||(old!=null&&old.side()!=e.getValue().side())))throw new IllegalArgumentException("UI_PATCH_RESOURCE_SIDE");
        }
        if(base.canonicalSha256().equals(candidate.canonicalSha256()))throw new IllegalArgumentException("UI_PATCH_NO_CHANGE");
    }
    public static boolean ui(String path){return path.startsWith("ui/");}
}
