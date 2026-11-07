package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Adopts locally persisted legacy packages only. Imports must supply the new authenticated format.
 * Historical records stay immutable. A sidecar binds the exact old record to a new signed envelope,
 * retaining all package/resource IDs and lifecycle revisions. No legacy file digest is recomputed.
 */
final class PackageSignatureMigration {
    private static final String NS="package_signature_migration_v2";
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Receipt(String original,RuntimePackage signed){}
    private final SqliteRuntimeRepository repository;private final ContentAddressedStore content;private final IdentitySigner signer;private final byte[] key;
    PackageSignatureMigration(SqliteRuntimeRepository repository,ContentAddressedStore content,IdentitySigner signer,byte[] key){this.repository=repository;this.content=content;this.signer=signer;this.key=key.clone();}
    RuntimePackage readStored(String original)throws Exception{
        var p=JSON.readValue(original,RuntimePackage.class);if(PackageSignatures.modern(p))return p;
        String id=p.packageId()+"_"+p.revision();var existing=repository.get(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,NS,id);
        if(existing.isPresent())return checked(original,JSON.readValue(existing.get().payload(),Receipt.class));
        if(signer==null||content==null||!IdentitySigner.verify(key,p.canonicalSha256().getBytes(StandardCharsets.US_ASCII),Base64.getDecoder().decode(p.signature())))throw new SecurityException("LEGACY_PACKAGE_SIGNATURE_MIGRATION_REQUIRED");
        var migrated=PackageSignatures.signVersion(p,p.canonicalSha256(),content::read,signer);var receipt=new Receipt(original,migrated);
        var result=repository.compareAndSet(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,NS,id,0,JSON.writeValueAsString(receipt),System.currentTimeMillis());
        return result.accepted()?migrated:checked(original,JSON.readValue(result.record().payload(),Receipt.class));
    }
    private RuntimePackage checked(String original,Receipt receipt)throws Exception{
        if(!receipt.original().equals(original)||!PackageSignatures.verifyManifest(receipt.signed(),(bytes,proof)->IdentitySigner.verify(key,bytes,proof)))throw new SecurityException("LEGACY_PACKAGE_MIGRATION_RECORD_CHANGED");return receipt.signed();
    }
}
