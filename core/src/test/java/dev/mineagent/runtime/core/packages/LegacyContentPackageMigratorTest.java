package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.ContentPackage;
import dev.mineagent.runtime.api.packages.PackageOrigin;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyContentPackageMigratorTest {
    @TempDir
    Path temporaryDirectory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void backsUpAndMigratesLegacySourceWithoutLosingIdentityOrSignatureEvidence() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        Path casRoot = temporaryDirectory.resolve("objects");
        UUID worldId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        String source = "on('tick', function () {});";
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"))) {
            String legacyHash = dev.mineagent.runtime.api.packages.ResourceIds.create();
            String legacySignature = Base64.getEncoder().encodeToString(
                    signer.sign(legacyHash.getBytes(StandardCharsets.US_ASCII)));
            try(var repository=new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(database)){var legacy=new ContentPackage(packageId,"legacy-wind","1.0.0",ActivationMode.HOT_RUNTIME,Map.of(),Set.of("world.read"),source,legacyHash,legacySignature,true,1,0);assertTrue(repository.compareAndSet(worldId,"content_packages",packageId.toString(),0,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(legacy),0).accepted());}

            try (var library = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded());
                 var ownership = PackageResourceOwnershipService.open(database, clock)) {
                var migrator = new LegacyContentPackageMigrator(database, new ContentAddressedStore(casRoot),
                        library, ownership, signer, clock);
                var first = migrator.migrateWorld(worldId);
                var second = migrator.migrateWorld(worldId);

                assertEquals(1, first.migratedCount());
                assertEquals(0, second.migratedCount());
                assertTrue(Files.isRegularFile(first.backupPath()));
                var migrated = library.get(packageId).orElseThrow();
                assertEquals(PackageOrigin.LEGACY_V1, migrated.origin());
                assertEquals(packageId, migrated.packageId());
                assertEquals(legacyHash, migrated.entrypoints().get("server").sha256());
                assertEquals(source, Files.readString(new ContentAddressedStore(casRoot)
                        .pathFor(legacyHash), StandardCharsets.UTF_8));
                var evidence = first.receipt().packages().get(packageId);
                assertEquals(legacyHash, evidence.legacySha256());
                assertEquals(legacySignature, evidence.legacySignature());
            }
        }
    }
}
