package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContentPackageServiceTest {
    @TempDir
    Path temporaryDirectory;
    private final UUID world = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void installsSignedDependenciesAndPersistsEnableState() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        try (var identity = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var service = ContentPackageService.open(database, world, clock, identity.publicKeyEncoded())) {
            var base = signed(identity, "base", "1.0.0", Map.of());
            var addon = signed(identity, "addon", "1.0.0", Map.of(base.packageId(), "1.0.0"));

            assertTrue(service.install(base).accepted());
            assertTrue(service.install(addon).accepted());
            var disabled = service.setEnabled(addon.packageId(), 1, false);
            assertTrue(disabled.accepted());
            assertFalse(disabled.contentPackage().enabled());
        }
        try (var identity = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var reopened = ContentPackageService.open(database, world, clock, identity.publicKeyEncoded())) {
            assertEquals(2, reopened.all().size());
            assertTrue(reopened.all().stream().anyMatch(contentPackage -> !contentPackage.enabled()));
        }
    }

    @Test
    void rejectsMissingDependencyTamperedHashAndSignature() throws Exception {
        try (var identity = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var service = ContentPackageService.open(
                     temporaryDirectory.resolve("runtime.db"), world, clock, identity.publicKeyEncoded())) {
            var missing = signed(identity, "addon", "1.0.0", Map.of(UUID.randomUUID(), "1.0.0"));
            var valid = signed(identity, "base", "1.0.0", Map.of());

            assertEquals("DEPENDENCY_MISSING", service.install(missing).errorCode());
            assertEquals("SIGNATURE_INVALID", service.install(valid.withSource("tampered")).errorCode());
            assertEquals("SIGNATURE_INVALID", service.install(valid.withSignature("AA==")).errorCode());
        }
    }

    @Test
    void upgradesSignedPackageWithCasAndProtectsEnabledDependents() throws Exception {
        try (var identity = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var service = ContentPackageService.open(
                     temporaryDirectory.resolve("upgrade.db"), world, clock, identity.publicKeyEncoded())) {
            var base = signed(identity, "base", "1.0.0", Map.of());
            var addon = signed(identity, "addon", "1.0.0", Map.of(base.packageId(), "1.0.0"));
            assertTrue(service.install(base).accepted());
            assertTrue(service.install(addon).accepted());
            var base2 = signedWithId(identity, base.packageId(), "base", "2.0.0", Map.of());

            assertEquals("DEPENDENT_VERSION_CONFLICT", service.upgrade(base2, 1).errorCode());
            service.setEnabled(addon.packageId(), 1, false);
            var upgraded = service.upgrade(base2, 1);

            assertTrue(upgraded.accepted());
            assertEquals("2.0.0", upgraded.contentPackage().version());
            assertEquals(2, upgraded.contentPackage().revision());
            assertEquals("STALE_REVISION", service.upgrade(base2, 1).errorCode());
        }
    }

    private dev.mineagent.runtime.api.packages.ContentPackage signed(
            IdentitySigner identity,
            String name,
            String version,
            Map<UUID, String> dependencies
    ) throws Exception {
        return signedWithId(identity, UUID.randomUUID(), name, version, dependencies);
    }

    private dev.mineagent.runtime.api.packages.ContentPackage signedWithId(
            IdentitySigner identity,
            UUID packageId,
            String name,
            String version,
            Map<UUID, String> dependencies
    ) throws Exception {
        String source = "// " + name + " " + version;
        String hash = dev.mineagent.runtime.api.packages.ResourceIds.create();
        String signature = java.util.Base64.getEncoder().encodeToString(
                dev.mineagent.runtime.core.crypto.SourceSignature.sign(identity,hash,source));
        return new dev.mineagent.runtime.api.packages.ContentPackage(
                packageId, name, version, ActivationMode.HOT_RUNTIME,
                dependencies, Set.of("world.read"), source, hash, signature, true, 1, 0);
    }
}
