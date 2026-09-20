package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.packages.PackageOrigin;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExamplePackageCatalog implements AutoCloseable {
    private static final String NAMESPACE = "example_imports";
    private final Map<String, RuntimePackage> examples;
    private final Map<String, RuntimePackage> imported = new LinkedHashMap<>();
    private final SqliteRuntimeRepository repository;
    private final Clock clock;
    private final RuntimePackageLibrary library;
    private final IdentitySigner signer;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private ExamplePackageCatalog(
            SqliteRuntimeRepository repository,
            Clock clock,
            RuntimePackageLibrary library,
            IdentitySigner signer,
            Map<String, RuntimePackage> examples
    ) throws Exception {
        this.repository = repository;
        this.clock = clock;
        this.library = library;
        this.signer = signer;
        this.examples = Map.copyOf(examples);
        for (var record : repository.list(RuntimePackageLibrary.GLOBAL_LIBRARY_ID, NAMESPACE)) {
            ExampleImportReceipt receipt = mapper.readValue(record.payload(), ExampleImportReceipt.class);
            library.get(receipt.packageId()).ifPresent(runtimePackage -> imported.put(receipt.exampleId(), runtimePackage));
        }
    }

    public static ExamplePackageCatalog open(
            Path database,
            Clock clock,
            RuntimePackageLibrary library,
            IdentitySigner signer,
            Map<String, RuntimePackage> examples
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new ExamplePackageCatalog(repository, clock, library, signer, examples);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized RuntimePackage importExplicitly(String exampleId) throws Exception {
        RuntimePackage alreadyImported = imported.get(exampleId);
        if (alreadyImported != null) {
            return alreadyImported;
        }
        RuntimePackage runtimePackage = examples.get(exampleId);
        if (runtimePackage == null) {
            throw new IllegalArgumentException("unknown example package");
        }
        RuntimePackage importedPackage = withOrigin(runtimePackage, PackageOrigin.EXPLICIT_IMPORT);
        var installed = library.install(importedPackage);
        if (!installed.accepted()) {
            throw new IllegalStateException("example import failed: " + installed.errorCode());
        }
        ExampleImportReceipt receipt = new ExampleImportReceipt(exampleId, importedPackage.packageId(),
                runtimePackage.canonicalSha256(), clock.millis());
        var saved = repository.compareAndSet(RuntimePackageLibrary.GLOBAL_LIBRARY_ID, NAMESPACE, exampleId, 0,
                mapper.writeValueAsString(receipt), receipt.importedAtEpochMillis());
        if (!saved.accepted()) {
            throw new IllegalStateException("example import receipt conflict");
        }
        imported.put(exampleId, installed.runtimePackage());
        return installed.runtimePackage();
    }

    public synchronized List<RuntimePackage> importedPackages() {
        return List.copyOf(imported.values());
    }

    private RuntimePackage withOrigin(RuntimePackage source, PackageOrigin origin) throws Exception {
        RuntimePackage unsigned = new RuntimePackage(source.packageId(), source.type(), source.name(), source.version(),
                source.activationMode(), source.dependencies(), source.permissions(), source.entrypoints(),
                source.definitions(), source.resources(), origin, false, 1, "0".repeat(64), "", 0,source.nativeCompatibility());
        String hash = RuntimePackageCanonicalizer.sha256(unsigned);
        String signature = Base64.getEncoder().encodeToString(
                signer.sign(hash.getBytes(StandardCharsets.US_ASCII)));
        return new RuntimePackage(unsigned.packageId(), unsigned.type(), unsigned.name(), unsigned.version(),
                unsigned.activationMode(), unsigned.dependencies(), unsigned.permissions(), unsigned.entrypoints(),
                unsigned.definitions(), unsigned.resources(), unsigned.origin(), false, 1, hash, signature, 0,unsigned.nativeCompatibility());
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
