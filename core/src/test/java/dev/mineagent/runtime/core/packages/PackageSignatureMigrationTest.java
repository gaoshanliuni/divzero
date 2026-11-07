package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;import dev.mineagent.runtime.core.crypto.IdentitySigner;import dev.mineagent.runtime.core.content.ContentAddressedStore;import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.time.Clock;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PackageSignatureMigrationTest {
 @TempDir Path root;
 @Test void storedLegacyRecordGetsOneSidecarAndKeepsHistoricalIdentity()throws Exception{
  Path db=root.resolve("runtime.db");var content=new ContentAddressedStore(root.resolve("content"));var file=content.put(new byte[]{1,2,3,4});String id=ResourceIds.create();UUID pkgId=UUID.randomUUID();
  try(var identity=IdentitySigner.open(root.resolve("identity"))){
   var resource=new RuntimeResourceRef("server/main.js",file.sha256(),RuntimeResourceSide.SERVER,"application/javascript",4);
   // Represents a pre-upgrade persisted signature over an opaque historical ID. No digest is computed.
   String signature=Base64.getEncoder().encodeToString(identity.sign(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
   var legacy=new RuntimePackage(pkgId,RuntimePackageType.CONTENT,"legacy","1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("server",new RuntimeEntrypoint(resource.path(),resource.side(),file.sha256())),Map.of(),Map.of(resource.path(),resource),PackageOrigin.GENERATED,false,1,id,signature,0);
   String raw=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(legacy);
   try(var repository=new SqliteRuntimeRepository(db)){assertTrue(repository.compareAndSet(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,"runtime_packages_v2",pkgId.toString(),0,raw,0).accepted());}
   String migrated;
   try(var library=RuntimePackageLibrary.open(db,Clock.systemUTC(),identity,content)){var found=library.get(pkgId).orElseThrow();assertEquals(id,found.canonicalSha256());assertEquals(1,found.revision());assertTrue(PackageSignatures.modern(found));migrated=found.signature();}
   try(var library=RuntimePackageLibrary.open(db,Clock.systemUTC(),identity.publicKeyEncoded(),content)){assertEquals(migrated,library.get(pkgId).orElseThrow().signature());}
   try(var repository=new SqliteRuntimeRepository(db)){assertEquals(raw,repository.get(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,"runtime_packages_v2",pkgId.toString()).orElseThrow().payload());assertEquals(1,repository.list(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,"package_signature_migration_v2").size());}
   Files.write(file.path(),new byte[]{1,2,3,5});
   try(var library=RuntimePackageLibrary.open(db,Clock.systemUTC(),identity.publicKeyEncoded(),content)){assertTrue(library.get(pkgId).isEmpty());}
  }
 }
}
