package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.api.packages.CodeDraftStatus;
import dev.mineagent.runtime.api.packages.NativeCompatibility;
import dev.mineagent.runtime.api.packages.PackageOrigin;
import dev.mineagent.runtime.api.packages.RuntimeDefinition;
import dev.mineagent.runtime.api.packages.RuntimeDefinitionKind;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.packages.RuntimePackageType;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePackageLibraryTest {
    @TempDir
    Path temporaryDirectory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    @Test void historicalCopyIsIndependentAndFencesCurrentHead()throws Exception{
        try(var signer=IdentitySigner.open(temporaryDirectory.resolve("history-id"));var library=RuntimePackageLibrary.open(temporaryDirectory.resolve("history.db"),clock,signer.publicKeyEncoded())){
            UUID id=UUID.randomUUID(),world=UUID.randomUUID(),owner=UUID.randomUUID();var first=signed(signer,id,"1.0.0",true);assertTrue(library.install(first).accepted());assertTrue(library.setEnabled(id,1,false).accepted());
            var source=library.versionByCanonical(id,1,first.canonicalSha256());var input=new PackageAssetMetadata.Input(UUID.randomUUID(),world,owner,"COPY_VERSION",id,1,first.canonicalSha256(),"历史副本",null,2);
            var candidate=RuntimePackageAssetCopy.prepare(source,input.targetId(),input.name(),signer);var receipt=library.installAssetCopy(input,candidate);assertEquals(input.targetId(),receipt.target());assertEquals(2,library.get(id).orElseThrow().revision());assertFalse(library.get(id).orElseThrow().enabled());assertEquals(first.definitions(),library.get(receipt.target()).orElseThrow().definitions());assertEquals(receipt,library.installAssetCopy(input,candidate));
            assertTrue(library.setEnabled(id,2,true).accepted());var stale=new PackageAssetMetadata.Input(UUID.randomUUID(),world,owner,"COPY_VERSION",id,1,first.canonicalSha256(),"旧读取",null,2);var other=RuntimePackageAssetCopy.prepare(source,stale.targetId(),stale.name(),signer);org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->library.installAssetCopy(stale,other));assertTrue(library.get(stale.targetId()).isEmpty());
        }
    }

    @Test void historicalNonHotCopyKeepsLifecycleAndNeverEnablesSourceOrCopy()throws Exception{
        try(var signer=IdentitySigner.open(temporaryDirectory.resolve("nonhot-id"));var library=RuntimePackageLibrary.open(temporaryDirectory.resolve("nonhot.db"),clock,signer.publicKeyEncoded())){
            for(var mode:ActivationMode.values()){
                if(mode==ActivationMode.HOT_RUNTIME)continue;
                UUID id=UUID.randomUUID(),world=UUID.randomUUID(),owner=UUID.randomUUID();var base=signed(signer,id,"1.0.0",false);
                var unsigned=new RuntimePackage(id,base.type(),base.name(),base.version(),mode,base.dependencies(),base.permissions(),base.entrypoints(),base.definitions(),base.resources(),base.origin(),false,1,"0".repeat(64),"",0);
                String hash=RuntimePackageCanonicalizer.sha256(unsigned);
                var first=new RuntimePackage(id,unsigned.type(),unsigned.name(),unsigned.version(),mode,unsigned.dependencies(),unsigned.permissions(),unsigned.entrypoints(),unsigned.definitions(),unsigned.resources(),unsigned.origin(),false,1,hash,Base64.getEncoder().encodeToString(signer.sign(hash.getBytes(StandardCharsets.US_ASCII))),0);
                assertTrue(library.install(first).accepted());assertTrue(library.setEnabled(id,1,true).accepted());assertTrue(library.setEnabled(id,2,false).accepted());
                var source=library.versionByCanonical(id,1,hash);var input=new PackageAssetMetadata.Input(UUID.randomUUID(),world,owner,"COPY_VERSION",id,1,hash,"历史重载副本",null,3);
                var copy=RuntimePackageAssetCopy.prepare(source,input.targetId(),input.name(),signer);var receipt=library.installAssetCopy(input,copy);var stored=library.get(receipt.target()).orElseThrow();
                assertEquals(mode,stored.activationMode());assertFalse(stored.enabled());assertEquals(PackageOrigin.REUSED,stored.origin());assertEquals(first.resources(),stored.resources());assertEquals(first.entrypoints(),stored.entrypoints());
                assertFalse(library.get(id).orElseThrow().enabled());assertEquals(3,library.get(id).orElseThrow().revision());assertEquals(receipt,library.installAssetCopy(input,copy));
                var stale=new PackageAssetMetadata.Input(UUID.randomUUID(),world,owner,"COPY_VERSION",id,1,hash,"过期读取",null,2);
                var denied=RuntimePackageAssetCopy.prepare(source,stale.targetId(),stale.name(),signer);org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->library.installAssetCopy(stale,denied));assertTrue(library.get(stale.targetId()).isEmpty());
            }
        }
    }

    @Test
    void persistsPackagesInGlobalLibraryAndUsesCasForMutations() throws Exception {
        Path database = temporaryDirectory.resolve("global.db");
        UUID packageId = UUID.randomUUID();
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var library = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded())) {
            RuntimePackage first = signed(signer, packageId, "1.0.0", true);

            assertTrue(library.install(first).accepted());
            assertEquals("STALE_REVISION", library.setEnabled(packageId, 2, false).errorCode());
            var disabled = library.setEnabled(packageId, 1, false);
            assertTrue(disabled.accepted());
            assertFalse(disabled.runtimePackage().enabled());
        }

        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var reopened = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded())) {
            assertEquals(1, reopened.all().size());
            assertFalse(reopened.get(packageId).orElseThrow().enabled());
        }
    }

    @Test
    void rejectsTamperedCanonicalManifest() throws Exception {
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var library = RuntimePackageLibrary.open(
                     temporaryDirectory.resolve("tamper.db"), clock, signer.publicKeyEncoded())) {
            RuntimePackage signed = signed(signer, UUID.randomUUID(), "1.0.0", true);
            RuntimePackage tampered = new RuntimePackage(signed.packageId(), signed.type(), "changed",
                    signed.version(), signed.activationMode(), signed.dependencies(), signed.permissions(),
                    signed.entrypoints(), signed.definitions(), signed.resources(), signed.origin(), signed.enabled(),
                    signed.revision(), signed.canonicalSha256(), signed.signature(), signed.updatedAtEpochMillis());

            assertEquals("HASH_MISMATCH", library.install(tampered).errorCode());
        }
    }

    @Test
    void upgradeCannotDisableThroughSideDoorAndRejectsDisabledOrCyclicDependencies() throws Exception {
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var library = RuntimePackageLibrary.open(
                     temporaryDirectory.resolve("dependencies.db"), clock, signer.publicKeyEncoded())) {
            UUID baseId = UUID.randomUUID();
            UUID addonId = UUID.randomUUID();
            var base = signed(signer, baseId, "base", "1.0.0", true, Map.of());
            assertTrue(library.install(base).accepted());
            var disabledBase = signed(signer, baseId, "base", "1.0.1", false, Map.of());
            var upgraded = library.upgrade(disabledBase, 1);
            assertTrue(upgraded.accepted());
            assertTrue(upgraded.runtimePackage().enabled());

            assertTrue(library.setEnabled(baseId, 2, false).accepted());
            var addon = signed(signer, addonId, "addon", "1.0.0", true, Map.of(baseId, "1.0.1"));
            assertEquals("DEPENDENCY_DISABLED", library.install(addon).errorCode());
            assertTrue(library.setEnabled(baseId, 3, true).accepted());
            assertTrue(library.install(addon).accepted());

            var cyclicBase = signed(signer, baseId, "base", "1.0.1", true,
                    Map.of(addonId, "1.0.0"));
            assertEquals("DEPENDENCY_CYCLE", library.upgrade(cyclicBase, 4).errorCode());
        }
    }

    @Test
    void quarantinesOfflineTamperingAndMissingContentOnRestart() throws Exception {
        Path database = temporaryDirectory.resolve("quarantine.db");
        UUID packageId = UUID.randomUUID();
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"))) {
            RuntimePackage installed = signed(signer, packageId, "1.0.0", false);
            try (var library = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded())) {
                assertTrue(library.install(installed).accepted());
            }
            RuntimePackage tampered = new RuntimePackage(installed.packageId(), installed.type(), "tampered",
                    installed.version(), installed.activationMode(), installed.dependencies(), installed.permissions(),
                    installed.entrypoints(), installed.definitions(), installed.resources(), installed.origin(),
                    installed.enabled(), 2, installed.canonicalSha256(), installed.signature(), 1);
            try (var repository = new SqliteRuntimeRepository(database)) {
                assertTrue(repository.compareAndSet(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,
                        "runtime_packages_v2", packageId.toString(), 1,
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tampered), 1).accepted());
            }
            try (var reopened = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded())) {
                assertTrue(reopened.get(packageId).isEmpty());
                assertEquals("HASH_MISMATCH", reopened.quarantined().get(packageId));
            }

            Path missingDatabase = temporaryDirectory.resolve("missing-content.db");
            try (var library = RuntimePackageLibrary.open(missingDatabase, clock, signer.publicKeyEncoded())) {
                assertTrue(library.install(installed).accepted());
            }
            try (var reopened = RuntimePackageLibrary.open(missingDatabase, clock, signer.publicKeyEncoded(),
                    new ContentAddressedStore(temporaryDirectory.resolve("empty-cas")))) {
                assertEquals("RESOURCE_MISSING", reopened.quarantined().get(packageId));
            }
        }
    }

    @Test
    void malformedSignatureReturnsStableErrorInsteadOfThrowing() throws Exception {
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("identity"));
             var library = RuntimePackageLibrary.open(
                     temporaryDirectory.resolve("signature.db"), clock, signer.publicKeyEncoded())) {
            RuntimePackage signed = signed(signer, UUID.randomUUID(), "1.0.0", false);
            RuntimePackage malformed = new RuntimePackage(signed.packageId(), signed.type(), signed.name(),
                    signed.version(), signed.activationMode(), signed.dependencies(), signed.permissions(),
                    signed.entrypoints(), signed.definitions(), signed.resources(), signed.origin(), signed.enabled(),
                    signed.revision(), signed.canonicalSha256(), Base64.getEncoder().encodeToString(new byte[1]), 0);

            assertEquals("SIGNATURE_INVALID", library.install(malformed).errorCode());
        }
    }

    @Test
    void studioPublicationAcceptsSideBoundClientRhinoAndJavaSources() throws Exception {
        Path database = temporaryDirectory.resolve("client-studio.db");
        var content = new ContentAddressedStore(temporaryDirectory.resolve("client-studio-content"));
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("client-studio-identity"))) {
            var rhino = clientStudioFixture(signer, world, owner, false,
                    "client.status('RHINO_MANUAL');");
            var javaFixture = clientStudioFixture(signer, world, owner, true,
                    "package sample; public final class Entry {}");
            content.put(rhino.draft().source().getBytes(StandardCharsets.UTF_8));
            content.put(javaFixture.draft().source().getBytes(StandardCharsets.UTF_8));
            try (var repository = new SqliteRuntimeRepository(database)) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                assertTrue(repository.compareAndSet(world, "code_drafts", rhino.draft().draftId().toString(),
                        0, mapper.writeValueAsString(rhino.draft()), clock.millis()).accepted());
                assertTrue(repository.compareAndSet(world, "code_drafts", javaFixture.draft().draftId().toString(),
                        0, mapper.writeValueAsString(javaFixture.draft()), clock.millis()).accepted());
            }
            try (var library = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded(), content)) {
                for (var fixture : java.util.List.of(rhino, javaFixture)) {
                    String fingerprint = CodeDraftSources.fingerprint(fixture.draft());
                    var receipt = library.publishStudio(new JavaStudioMetadata.Input(UUID.randomUUID(), world,
                            owner, fixture.draft().draftId(), 1, fixture.draft().packageId(), 0,
                            fingerprint, fixture.manifest().name()), fixture.manifest());
                    assertEquals("SOURCE_PUBLISHED_NOT_EXECUTED", receipt.outcome());
                    var stored = library.get(fixture.draft().packageId()).orElseThrow();
                    if (fixture.java()) assertEquals(fixture.draft().path(), ClientJavaPlan.inspect(stored).entrypoint());
                    else assertEquals(fixture.draft().path(), ClientScriptPlan.inspect(stored).entrypoint());
                    assertFalse(stored.enabled());
                    assertNotEquals(fixture.draft().source(), receipt.canonical());
                }
            }
        }
    }

    @Test
    void serverAndClientStudioSourceSetsRemainIndependentInOnePackage() throws Exception {
        String serverPath = "server/sample/Entry.java", clientPath = "client/main.js";
        byte[] serverSource = "package sample; public final class Entry {}".getBytes(StandardCharsets.UTF_8);
        byte[] clientSource = "client.status('READY');".getBytes(StandardCharsets.UTF_8);
        String serverHash = RuntimePackageCanonicalizer.sha256(serverSource);
        String clientHash = RuntimePackageCanonicalizer.sha256(clientSource);
        var serverRef = new dev.mineagent.runtime.api.packages.RuntimeResourceRef(serverPath, serverHash,
                RuntimeResourceSide.SERVER, "text/x-java-source", serverSource.length);
        var clientRef = new dev.mineagent.runtime.api.packages.RuntimeResourceRef(clientPath, clientHash,
                RuntimeResourceSide.CLIENT, "application/javascript", clientSource.length);
        var target = new NativeCompatibility.Target("26.1.2", "neoforge", "26.1.2.106",
                "official", 25, Map.of());
        var manifest = new RuntimePackage(UUID.randomUUID(), RuntimePackageType.EXTENSION, "mixed studio",
                "1.0.0", ActivationMode.HOT_RUNTIME, Map.of(), Set.of("RUN_CODE"), Map.of(
                "java", new RuntimeEntrypoint(serverPath, RuntimeResourceSide.SERVER, serverHash),
                "client", new RuntimeEntrypoint(clientPath, RuntimeResourceSide.CLIENT, clientHash)), Map.of(),
                Map.of(serverPath, serverRef, clientPath, clientRef), PackageOrigin.LOCAL_STUDIO, false, 1,
                "0".repeat(64), "", clock.millis(), new NativeCompatibility(1,
                Map.of("SERVER", target, "CLIENT", target)));

        assertEquals(Set.of(serverPath), RuntimeStudioPlan.refs(manifest).keySet());
        assertEquals(Set.of(clientPath), ClientScriptPlan.inspect(manifest).modules().keySet());
        assertEquals(serverPath, RuntimeStudioPlan.source(manifest).path());
        assertEquals(clientPath, ClientScriptPlan.inspect(manifest).entrypoint());
    }

    @Test
    void serverStudioPublicationKeepsTheLegacySourceFingerprintContract() throws Exception {
        Path database = temporaryDirectory.resolve("server-studio.db");
        var content = new ContentAddressedStore(temporaryDirectory.resolve("server-studio-content"));
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID(), packageId = UUID.randomUUID();
        String path = "server/studio.js", source = "host.value();";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        String sourceHash = RuntimePackageCanonicalizer.sha256(bytes);
        var draft = new CodeDraft(UUID.randomUUID(), world, owner, UUID.randomUUID(), packageId, 1, 1, 1,
                path, source, CodeDraftStatus.DRAFT, java.util.List.of(), clock.millis());
        assertEquals(sourceHash, CodeDraftSources.fingerprint(draft));
        content.put(bytes);
        try (var repository = new SqliteRuntimeRepository(database)) {
            assertTrue(repository.compareAndSet(world, "code_drafts", draft.draftId().toString(), 0,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft),
                    clock.millis()).accepted());
        }
        try (var signer = IdentitySigner.open(temporaryDirectory.resolve("server-studio-identity"))) {
            var ref = new dev.mineagent.runtime.api.packages.RuntimeResourceRef(path, sourceHash,
                    RuntimeResourceSide.SERVER, "application/javascript", bytes.length);
            var compatibility = new NativeCompatibility(1, Map.of("SERVER", new NativeCompatibility.Target(
                    "26.1.2", "neoforge", "26.1.2.106", "official", 25, Map.of())));
            var unsigned = new RuntimePackage(packageId, RuntimePackageType.EXTENSION, "server studio", "1.0.0",
                    ActivationMode.HOT_RUNTIME, Map.of(), Set.of("RUN_CODE"), Map.of("studio_script",
                    new RuntimeEntrypoint(path, RuntimeResourceSide.SERVER, sourceHash)), Map.of(), Map.of(path, ref),
                    PackageOrigin.LOCAL_STUDIO, false, 1, "0".repeat(64), "", clock.millis(), compatibility);
            String canonical = RuntimePackageCanonicalizer.sha256(unsigned);
            var manifest = new RuntimePackage(unsigned.packageId(), unsigned.type(), unsigned.name(),
                    unsigned.version(), unsigned.activationMode(), unsigned.dependencies(), unsigned.permissions(),
                    unsigned.entrypoints(), unsigned.definitions(), unsigned.resources(), unsigned.origin(), false,
                    1, canonical, Base64.getEncoder().encodeToString(signer.sign(
                    canonical.getBytes(StandardCharsets.US_ASCII))), unsigned.updatedAtEpochMillis(), compatibility);
            try (var library = RuntimePackageLibrary.open(database, clock, signer.publicKeyEncoded(), content)) {
                var receipt = library.publishStudio(new JavaStudioMetadata.Input(UUID.randomUUID(), world, owner,
                        draft.draftId(), 1, packageId, 0, sourceHash, manifest.name()), manifest);
                assertEquals("SOURCE_PUBLISHED_NOT_EXECUTED", receipt.outcome());
                assertEquals(sourceHash, RuntimeStudioPlan.fingerprint(library.get(packageId).orElseThrow()));
            }
        }
    }

    private record ClientStudioFixture(CodeDraft draft, RuntimePackage manifest, boolean java) {}

    private ClientStudioFixture clientStudioFixture(IdentitySigner signer, UUID world, UUID owner,
                                                    boolean javaSource, String source) throws Exception {
        UUID packageId = UUID.randomUUID(), draftId = UUID.randomUUID();
        String path = javaSource ? "client/sample/Entry.java" : "client/main.js";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        String resourceHash = RuntimePackageCanonicalizer.sha256(bytes);
        var draft = new CodeDraft(draftId, world, owner, UUID.randomUUID(), packageId, 1, 1, 1,
                path, source, CodeDraftStatus.DRAFT, java.util.List.of(), clock.millis());
        var ref = new dev.mineagent.runtime.api.packages.RuntimeResourceRef(path, resourceHash,
                RuntimeResourceSide.CLIENT, javaSource ? "text/x-java-source" : "application/javascript",
                bytes.length);
        var compatibility = new NativeCompatibility(1, Map.of("CLIENT", new NativeCompatibility.Target(
                "26.1.2", "neoforge", "26.1.2.106", "official", 25, Map.of())));
        String entryId = javaSource ? "client_java" : "client";
        var unsigned = new RuntimePackage(packageId, RuntimePackageType.EXTENSION,
                javaSource ? "manual client java" : "manual client rhino", "1.0.0",
                ActivationMode.HOT_RUNTIME, Map.of(), Set.of(), Map.of(entryId,
                new RuntimeEntrypoint(path, RuntimeResourceSide.CLIENT, resourceHash)), Map.of(),
                Map.of(path, ref), PackageOrigin.LOCAL_STUDIO, false, 1, "0".repeat(64), "",
                clock.millis(), compatibility);
        String canonical = RuntimePackageCanonicalizer.sha256(unsigned);
        var manifest = new RuntimePackage(unsigned.packageId(), unsigned.type(), unsigned.name(),
                unsigned.version(), unsigned.activationMode(), unsigned.dependencies(), unsigned.permissions(),
                unsigned.entrypoints(), unsigned.definitions(), unsigned.resources(), unsigned.origin(), false,
                1, canonical, Base64.getEncoder().encodeToString(signer.sign(
                canonical.getBytes(StandardCharsets.US_ASCII))), unsigned.updatedAtEpochMillis(), compatibility);
        return new ClientStudioFixture(draft, manifest, javaSource);
    }

    static RuntimePackage signed(IdentitySigner signer, UUID packageId, String version, boolean enabled)
            throws Exception {
        return signed(signer, packageId, "wind-chime", version, enabled, Map.of());
    }

    static RuntimePackage signed(
            IdentitySigner signer,
            UUID packageId,
            String name,
            String version,
            boolean enabled,
            Map<UUID, String> dependencies
    ) throws Exception {
        UUID definitionId = UUID.nameUUIDFromBytes((packageId + ":definition").getBytes(StandardCharsets.UTF_8));
        var sourceResource = new dev.mineagent.runtime.api.packages.RuntimeResourceRef(
                "server/main.js", "d".repeat(64), RuntimeResourceSide.SERVER,
                "application/javascript", 12);
        RuntimePackage unsigned = new RuntimePackage(packageId, RuntimePackageType.CONTENT, name, version,
                ActivationMode.HOT_RUNTIME, dependencies, Set.of("world.read"),
                Map.of("server", new RuntimeEntrypoint("server/main.js", RuntimeResourceSide.SERVER,
                        "d".repeat(64))),
                Map.of(definitionId, new RuntimeDefinition(definitionId, "风铃", RuntimeDefinitionKind.BLOCK,
                        "server", Set.of(), Map.of(), 1)),
                Map.of(sourceResource.path(), sourceResource), PackageOrigin.GENERATED, enabled, 1,
                "0".repeat(64), "", 0);
        String hash = RuntimePackageCanonicalizer.sha256(unsigned);
        String signature = Base64.getEncoder().encodeToString(
                signer.sign(hash.getBytes(StandardCharsets.US_ASCII)));
        return new RuntimePackage(packageId, unsigned.type(), unsigned.name(), unsigned.version(),
                unsigned.activationMode(), unsigned.dependencies(), unsigned.permissions(), unsigned.entrypoints(),
                unsigned.definitions(), unsigned.resources(), unsigned.origin(), enabled, 1, hash, signature, 0);
    }
}
