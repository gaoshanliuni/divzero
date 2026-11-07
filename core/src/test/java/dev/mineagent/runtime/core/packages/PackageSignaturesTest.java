package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;import dev.mineagent.runtime.core.crypto.IdentitySigner;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.Path;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PackageSignaturesTest {
 @TempDir Path root;
 private RuntimePackage draft(String id){var ref=new RuntimeResourceRef("server/main.js",id,RuntimeResourceSide.SERVER,"application/javascript",4);return new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,"test","1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("server",new RuntimeEntrypoint(ref.path(),ref.side(),id)),Map.of(),Map.of(ref.path(),ref),PackageOrigin.GENERATED,false,1,ResourceIds.create(),"",0);}
 @Test void authenticatesActualBytesWithoutAContentDigest()throws Exception{
  try(var signer=IdentitySigner.open(root.resolve("signer"));var other=IdentitySigner.open(root.resolve("other"))){
   String id=ResourceIds.create();byte[] data={1,2,3,4};var pkg=PackageSignatures.signNew(draft(id),key->data,signer);var ref=pkg.resources().get("server/main.js");
   assertTrue(PackageSignatures.verifyManifest(pkg,(bytes,proof)->IdentitySigner.verify(signer.publicKeyEncoded(),bytes,proof)));assertFalse(PackageSignatures.verifyManifest(pkg,(bytes,proof)->IdentitySigner.verify(other.publicKeyEncoded(),bytes,proof)));
   PackageSignatures.requireResource(pkg,ref,data);assertThrows(SecurityException.class,()->PackageSignatures.requireResource(pkg,ref,new byte[]{1,2,3,5}));
   var changed=new RuntimePackage(pkg.packageId(),pkg.type(),"changed",pkg.version(),pkg.activationMode(),pkg.dependencies(),pkg.permissions(),pkg.entrypoints(),pkg.definitions(),pkg.resources(),pkg.origin(),false,1,pkg.canonicalSha256(),pkg.signature(),0);
   assertFalse(PackageSignatures.verifyManifest(changed,(bytes,proof)->IdentitySigner.verify(signer.publicKeyEncoded(),bytes,proof)));
   var copy=PackageSignatures.copySigned(pkg,draft(id),signer);assertNotEquals(pkg.canonicalSha256(),copy.canonicalSha256());PackageSignatures.requireResource(copy,ref,data);
  }
 }
}
