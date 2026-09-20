package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.ContentPackage;
import dev.mineagent.runtime.api.packages.PackageOrigin;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.packages.RuntimePackageType;
import dev.mineagent.runtime.api.packages.RuntimeResourceRef;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LegacyContentPackageMigrator {
    private static final String LEGACY_NAMESPACE = "content_packages";
    private static final String RECEIPT_NAMESPACE = "runtime_migrations";
    private static final String MIGRATION_ID = "content_packages_v1_to_v2";

    private final Path database;
    private final ContentAddressedStore contentStore;
    private final RuntimePackageLibrary library;
    private final PackageResourceOwnershipService ownership;
    private final IdentitySigner signer;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacyContentPackageMigrator(
            Path database,
            ContentAddressedStore contentStore,
            RuntimePackageLibrary library,
            PackageResourceOwnershipService ownership,
            IdentitySigner signer,
            Clock clock
    ) {
        this.database = database.toAbsolutePath().normalize();
        this.contentStore = contentStore;
        this.library = library;
        this.ownership = ownership;
        this.signer = signer;
        this.clock = clock;
    }

    public synchronized LegacyMigrationResult migrateWorld(UUID worldId) throws Exception {
        Path backup = backupPath();
        try (var repository = new SqliteRuntimeRepository(database)) {
            var existingReceipt = repository.get(worldId, RECEIPT_NAMESPACE, MIGRATION_ID);
            if (existingReceipt.isPresent()) {
                return new LegacyMigrationResult(0, backup,
                        mapper.readValue(existingReceipt.get().payload(), LegacyMigrationReceipt.class));
            }
            if (!Files.isRegularFile(backup)) {
                Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            List<ContentPackage> legacyPackages = new ArrayList<>();
            for (var record : repository.list(worldId, LEGACY_NAMESPACE)) {
                legacyPackages.add(mapper.readValue(record.payload(), ContentPackage.class));
            }
            var evidence = new LinkedHashMap<UUID, LegacyPackageEvidence>();
            var pending = new ArrayList<>(legacyPackages);
            int migrated = 0;
            while (!pending.isEmpty()) {
                int before = pending.size();
                for (var iterator = pending.iterator(); iterator.hasNext(); ) {
                    ContentPackage legacy = iterator.next();
                    RuntimePackage converted = convert(legacy);
                    var result = library.install(converted);
                    if (result.accepted()) {
                        migrated++;
                    } else if ("DEPENDENCY_MISSING".equals(result.errorCode())) {
                        continue;
                    } else if (!"PACKAGE_EXISTS".equals(result.errorCode())
                            || result.runtimePackage().origin() != PackageOrigin.LEGACY_V1) {
                        throw new IllegalStateException("legacy package migration failed: " + result.errorCode());
                    }
                    ownership.acquire(legacy.packageId(), converted.resources().values());
                    evidence.put(legacy.packageId(), new LegacyPackageEvidence(
                            legacy.sha256(), legacy.signature(), legacy.revision(), "server/main.js"));
                    iterator.remove();
                }
                if (pending.size() == before) {
                    throw new IllegalStateException("legacy package dependency graph cannot be migrated");
                }
            }
            LegacyMigrationReceipt receipt = new LegacyMigrationReceipt(
                    worldId, MIGRATION_ID, evidence, clock.millis());
            var saved = repository.compareAndSet(worldId, RECEIPT_NAMESPACE, MIGRATION_ID, 0,
                    mapper.writeValueAsString(receipt), receipt.completedAtEpochMillis());
            if (!saved.accepted()) {
                receipt = mapper.readValue(saved.record().payload(), LegacyMigrationReceipt.class);
                migrated = 0;
            }
            return new LegacyMigrationResult(migrated, backup, receipt);
        }
    }

    private RuntimePackage convert(ContentPackage legacy) throws Exception {
        byte[] source = legacy.source().getBytes(StandardCharsets.UTF_8);
        var stored = contentStore.put(source);
        if (!stored.sha256().equals(legacy.sha256())) {
            throw new IllegalStateException("legacy source hash mismatch");
        }
        var resource = new RuntimeResourceRef("server/main.js", stored.sha256(), RuntimeResourceSide.SERVER,
                "application/javascript", stored.size());
        RuntimePackage unsigned = new RuntimePackage(legacy.packageId(), RuntimePackageType.CONTENT,
                legacy.name(), legacy.version(), legacy.activationMode(), legacy.dependencies(), legacy.permissions(),
                Map.of("server", new RuntimeEntrypoint("server/main.js", RuntimeResourceSide.SERVER,
                        stored.sha256())),
                Map.of(), Map.of(resource.path(), resource), PackageOrigin.LEGACY_V1, legacy.enabled(), 1,
                "0".repeat(64), "", 0);
        String canonicalHash = RuntimePackageCanonicalizer.sha256(unsigned);
        String signature = Base64.getEncoder().encodeToString(
                signer.sign(canonicalHash.getBytes(StandardCharsets.US_ASCII)));
        return new RuntimePackage(unsigned.packageId(), unsigned.type(), unsigned.name(), unsigned.version(),
                unsigned.activationMode(), unsigned.dependencies(), unsigned.permissions(), unsigned.entrypoints(),
                unsigned.definitions(), unsigned.resources(), unsigned.origin(), unsigned.enabled(), 1,
                canonicalHash, signature, 0);
    }

    private Path backupPath() {
        return database.resolveSibling(database.getFileName() + ".pre-runtime-package-v2.bak");
    }
}
