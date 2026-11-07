package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Copies code/definitions/resources only. No bindings, state, pins, approvals, tasks or instances are transported. */
public final class RuntimePackageAssetCopy {
    private RuntimePackageAssetCopy(){}
    public static RuntimePackage prepare(RuntimePackage source,UUID target,String name,IdentitySigner signer)throws Exception {
        if(source.packageId().equals(target)||name==null||name.isBlank()||name.length()>128||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("PACKAGE_COPY_NAME");

        // Definition IDs are package-qualified by RuntimeInstance; preserve literal source references, not instance IDs.
        var draft=new RuntimePackage(target,source.type(),name,source.version(),source.activationMode(),source.dependencies(),source.permissions(),source.entrypoints(),source.definitions(),source.resources(),PackageOrigin.REUSED,false,1,"0".repeat(64),"",System.currentTimeMillis(),source.nativeCompatibility());
        return PackageSignatures.copySigned(source,draft,signer);
    }
}
