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
        if(!RuntimePackageCanonicalizer.sha256(source).equals(source.canonicalSha256())||!IdentitySigner.verify(signer.publicKeyEncoded(),source.canonicalSha256().getBytes(StandardCharsets.US_ASCII),Base64.getDecoder().decode(source.signature())))throw new IllegalStateException("PACKAGE_COPY_SOURCE_SIGNATURE");
        // Definition IDs are package-qualified by RuntimeInstance; preserve literal source references, not instance IDs.
        var draft=new RuntimePackage(target,source.type(),name,source.version(),source.activationMode(),source.dependencies(),source.permissions(),source.entrypoints(),source.definitions(),source.resources(),PackageOrigin.REUSED,false,1,"0".repeat(64),"",System.currentTimeMillis(),source.nativeCompatibility());
        String hash=RuntimePackageCanonicalizer.sha256(draft);
        return new RuntimePackage(target,draft.type(),draft.name(),draft.version(),draft.activationMode(),draft.dependencies(),draft.permissions(),draft.entrypoints(),draft.definitions(),draft.resources(),draft.origin(),false,1,hash,Base64.getEncoder().encodeToString(signer.sign(hash.getBytes(StandardCharsets.US_ASCII))),draft.updatedAtEpochMillis(),draft.nativeCompatibility());
    }
}
