package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.content.ContentIntegrityException;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RuntimePackageLibrary implements AutoCloseable {
    public static final UUID GLOBAL_LIBRARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String NAMESPACE = "runtime_packages_v2";

    private final SqliteRuntimeRepository repository;
    private final Clock clock;
    private final byte[] trustedPublicKey;
    private final ContentAddressedStore contentStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, RuntimePackage> packages = new LinkedHashMap<>();
    private final Map<UUID, String> quarantined = new LinkedHashMap<>();

    private java.util.function.Consumer<UUID> mutationGuard=id->{};
    private boolean guardBound;
    public synchronized void bindMutationGuard(java.util.function.Consumer<UUID> guard){if(guardBound)throw new IllegalStateException("PACKAGE_MUTATION_GUARD_BOUND");mutationGuard=java.util.Objects.requireNonNull(guard);guardBound=true;}

    private RuntimePackageLibrary(
            SqliteRuntimeRepository repository,
            Clock clock,
            byte[] trustedPublicKey,
            ContentAddressedStore contentStore
    )
            throws Exception {
        this.repository = repository;
        this.clock = clock;
        this.trustedPublicKey = trustedPublicKey.clone();
        this.contentStore = contentStore;
        repository.initializePackageLibrary();
        var baselines=new java.util.ArrayList<dev.mineagent.runtime.core.persistence.RuntimeRecord>();
        for (var record : repository.list(GLOBAL_LIBRARY_ID, NAMESPACE)) {
            try {
                RuntimePackage runtimePackage = mapper.readValue(record.payload(), RuntimePackage.class);
                if(!runtimePackage.packageId().toString().equals(record.recordId())||runtimePackage.revision()!=record.revision())throw new IllegalArgumentException("PACKAGE_RECORD_CONTEXT");
                String error = integrityError(runtimePackage);
                if (error.isEmpty()) {
                    error = resourceError(runtimePackage);
                }
                if (error.isEmpty()) {
                    packages.put(runtimePackage.packageId(), runtimePackage);baselines.add(record);
                } else {
                    quarantined.put(runtimePackage.packageId(), error);
                }
            } catch (Exception corrupted) {
                try {
                    quarantined.put(UUID.fromString(record.recordId()), "CORRUPT_RECORD");
                } catch (IllegalArgumentException invalidId) {
                    // The invalid record remains unavailable and does not enter the active library.
                }
            }
        }
        repository.packageBaselines(baselines,clock.millis());
    }

    public static RuntimePackageLibrary open(Path database, Clock clock, byte[] trustedPublicKey) throws Exception {
        return open(database, clock, trustedPublicKey, null);
    }

    public static RuntimePackageLibrary open(
            Path database,
            Clock clock,
            byte[] trustedPublicKey,
            ContentAddressedStore contentStore
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new RuntimePackageLibrary(repository, clock, trustedPublicKey, contentStore);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized RuntimePackageMutationResult install(RuntimePackage candidate) throws Exception {
        if (candidate == null) {
            throw new IllegalArgumentException("runtime package is required");
        }
        if(quarantined.containsKey(candidate.packageId()))return RuntimePackageMutationResult.rejected(candidate,"PACKAGE_QUARANTINED");
        RuntimePackage existing = packages.get(candidate.packageId());
        if (existing != null) {
            return RuntimePackageMutationResult.rejected(existing, "PACKAGE_EXISTS");
        }
        String integrityError = integrityError(candidate);
        if (!integrityError.isEmpty()) {
            return RuntimePackageMutationResult.rejected(candidate, integrityError);
        }
        String resourceError = resourceError(candidate);
        if (!resourceError.isEmpty()) {
            return RuntimePackageMutationResult.rejected(candidate, resourceError);
        }
        String dependencyError = dependencyError(candidate, candidate.enabled());
        if (!dependencyError.isEmpty()) {
            return RuntimePackageMutationResult.rejected(candidate, dependencyError);
        }
        if (introducesCycle(candidate)) {
            return RuntimePackageMutationResult.rejected(candidate, "DEPENDENCY_CYCLE");
        }
        mutationGuard.accept(candidate.packageId());
        RuntimePackage stored = copy(candidate, candidate.enabled(), 1, clock.millis());
        var saved = repository.packageCompareAndSet(candidate.packageId(),0,mapper.writeValueAsString(stored),stored.updatedAtEpochMillis());
        if (!saved.accepted()) {
            RuntimePackage latest = readValidated(saved.record(),candidate);
            packages.put(latest.packageId(), latest);
            return RuntimePackageMutationResult.rejected(latest, "PACKAGE_EXISTS");
        }
        packages.put(stored.packageId(), stored);
        return RuntimePackageMutationResult.accepted(stored);
    }

    public synchronized RuntimePackageMutationResult upgrade(RuntimePackage candidate, long expectedRevision)
            throws Exception {
        RuntimePackage current = packages.get(candidate.packageId());
        if (current == null) {
            return RuntimePackageMutationResult.rejected(candidate, "PACKAGE_MISSING");
        }
        if (current.revision() != expectedRevision) {
            return RuntimePackageMutationResult.rejected(current, "STALE_REVISION");
        }
        String integrityError = integrityError(candidate);
        if (!integrityError.isEmpty()) {
            return RuntimePackageMutationResult.rejected(current, integrityError);
        }
        String resourceError = resourceError(candidate);
        if (!resourceError.isEmpty()) {
            return RuntimePackageMutationResult.rejected(current, resourceError);
        }
        String dependencyError = dependencyError(candidate, current.enabled());
        if (!dependencyError.isEmpty()) {
            return RuntimePackageMutationResult.rejected(current, dependencyError);
        }
        if (introducesCycle(candidate)) {
            return RuntimePackageMutationResult.rejected(current, "DEPENDENCY_CYCLE");
        }
        if (!current.version().equals(candidate.version()) && packages.values().stream()
                .anyMatch(other -> other.enabled() && !other.packageId().equals(current.packageId())
                        && current.version().equals(other.dependencies().get(current.packageId())))) {
            return RuntimePackageMutationResult.rejected(current, "DEPENDENT_VERSION_CONFLICT");
        }
        RuntimePackage next = copy(candidate, current.enabled(), current.revision() + 1, clock.millis());
        return save(current, next);
    }
    /** Read-only candidate verification; staging must not replace the active head. */
    public synchronized String validateCandidate(RuntimePackage candidate)throws Exception{
        String error=integrityError(candidate);if(error.isEmpty())error=resourceError(candidate);return error;
    }

    public synchronized RuntimePackageMutationResult setEnabled(
            UUID packageId,
            long expectedRevision,
            boolean enabled
    ) throws Exception {
        RuntimePackage current = packages.get(packageId);
        if (current == null) {
            throw new IllegalArgumentException("unknown runtime package");
        }
        if (current.revision() != expectedRevision) {
            return RuntimePackageMutationResult.rejected(current, "STALE_REVISION");
        }
        if (!enabled && packages.values().stream().anyMatch(other -> other.enabled()
                && other.dependencies().containsKey(packageId))) {
            return RuntimePackageMutationResult.rejected(current, "PACKAGE_REQUIRED");
        }
        if (enabled) {
            String dependencyError = dependencyError(current, true);
            if (!dependencyError.isEmpty()) {
                return RuntimePackageMutationResult.rejected(current, dependencyError);
            }
        }
        return save(current, copy(current, enabled, current.revision() + 1, clock.millis()));
    }

    public synchronized JavaStudioMetadata.Link studioLink(UUID world,UUID owner,UUID pkg)throws Exception{return repository.studioLink(world,owner,pkg);}
    public synchronized JavaStudioMetadata.Receipt studioReceipt(UUID world,UUID owner,UUID operation)throws Exception{return repository.studioReceipt(world,owner,operation);}
    public synchronized JavaStudioMetadata.Receipt publishStudio(JavaStudioMetadata.Input input,RuntimePackage pkg)throws Exception{
        String error=validateCandidate(pkg);if(error.isEmpty())error=dependencyError(pkg,false);if(error.isEmpty()&&introducesCycle(pkg))error="DEPENDENCY_CYCLE";if(!error.isEmpty())throw new IllegalStateException(error);
        mutationGuard.accept(pkg.packageId());
        var result=repository.studioPublish(input,mapper.writeValueAsString(pkg),clock.millis());refreshAsset(input.packageId());return result;
    }
    public synchronized PackageAssetMetadata.Alias alias(UUID owner,UUID pkg)throws Exception{return repository.packageAlias(owner,pkg);}
    public synchronized PackageAssetMetadata.Shelf shelf(UUID owner,UUID id)throws Exception{return repository.packageShelf(owner,id);}
    public synchronized PackageAssetMetadata.Page<PackageAssetMetadata.Shelf> shelves(UUID owner,boolean active,int offset)throws Exception{return repository.packageShelves(owner,active,offset);}
    public synchronized PackageAssetMetadata.Derivation derivation(UUID owner,UUID pkg)throws Exception{return repository.packageDerivation(owner,pkg);}
    public synchronized boolean copyOwned(UUID world,UUID owner,UUID pkg,long revision,String hash)throws Exception{return repository.packageCopyOwned(world,owner,pkg,revision,hash);}
    public synchronized PackageAssetMetadata.Receipt assetReceipt(UUID world,UUID owner,UUID operation)throws Exception{return repository.packageAssetReceipt(world,owner,operation);}
    public synchronized PackageAssetMetadata.Receipt assetMetadata(PackageAssetMetadata.Input input)throws Exception{return repository.packageAssetMetadata(input,clock.millis());}
    public synchronized RuntimePackage assetSource(PackageAssetMetadata.Shelf shelf)throws Exception{
        var record=repository.packageVersion(shelf.packageId(),shelf.packageRevision());if(record==null||!record.payloadHash().equals(shelf.payloadHash())||!RuntimePackageCanonicalizer.sha256(record.payload()).equals(shelf.payloadHash()))throw new IllegalStateException("PACKAGE_ASSET_VERSION_UNAVAILABLE");
        var p=mapper.readValue(record.payload(),RuntimePackage.class);if(!p.packageId().equals(shelf.packageId())||p.revision()!=shelf.packageRevision()||!p.version().equals(shelf.version())||!p.canonicalSha256().equals(shelf.canonical())||!integrityError(p).isEmpty())throw new IllegalStateException("PACKAGE_ASSET_SOURCE_SIGNATURE");return p;
    }
    public synchronized PackageAssetMetadata.Receipt installAssetCopy(PackageAssetMetadata.Input input,RuntimePackage candidate)throws Exception{
        String error=validateCandidate(candidate);if(error.isEmpty())error=dependencyError(candidate,false);if(error.isEmpty()&&introducesCycle(candidate))error="DEPENDENCY_CYCLE";if(!error.isEmpty())throw new IllegalStateException(error);
        mutationGuard.accept(candidate.packageId());
        var result=repository.packageAssetCopy(input,mapper.writeValueAsString(candidate),clock.millis());refreshAsset(result.target());return result;
    }
    public synchronized RuntimePackage refreshAsset(UUID id)throws Exception{
        var record=repository.get(GLOBAL_LIBRARY_ID,NAMESPACE,id.toString()).orElseThrow(()->new IllegalStateException("PACKAGE_COPY_COMMIT_UNCERTAIN"));var p=mapper.readValue(record.payload(),RuntimePackage.class);
        if(!p.packageId().equals(id)||p.revision()!=record.revision()||!integrityError(p).isEmpty()||!resourceError(p).isEmpty())throw new IllegalStateException("PACKAGE_COPY_COMMIT_UNCERTAIN");packages.put(id,p);return p;
    }
    public synchronized Optional<RuntimePackage> get(UUID packageId) {
        return Optional.ofNullable(packages.get(packageId));
    }

    public synchronized List<RuntimePackage> all() {
        return List.copyOf(packages.values());
    }

    public synchronized Map<UUID, String> quarantined() {
        return Map.copyOf(quarantined);
    }

    public synchronized int resourceReferenceCount(String sha256) {
        return packages.values().stream()
                .mapToInt(runtimePackage -> (int) runtimePackage.resources().values().stream()
                        .filter(resource -> resource.sha256().equals(sha256)).count())
                .sum();
    }

    public synchronized Set<UUID> resourceOwners(String sha256) {
        return packages.values().stream()
                .filter(runtimePackage -> runtimePackage.resources().values().stream()
                        .anyMatch(resource -> resource.sha256().equals(sha256)))
                .map(RuntimePackage::packageId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private RuntimePackageMutationResult save(RuntimePackage current, RuntimePackage next) throws Exception {
        mutationGuard.accept(current.packageId());
        var saved = repository.packageCompareAndSet(current.packageId(),current.revision(),mapper.writeValueAsString(next),next.updatedAtEpochMillis());
        if (!saved.accepted()) {
            RuntimePackage latest = readValidated(saved.record(),current);
            packages.put(latest.packageId(), latest);
            return RuntimePackageMutationResult.rejected(latest, "STALE_REVISION");
        }
        packages.put(next.packageId(), next);
        return RuntimePackageMutationResult.accepted(next);
    }

    private String integrityError(RuntimePackage candidate) throws Exception {
        if (!RuntimePackageCanonicalizer.sha256(candidate).equals(candidate.canonicalSha256())) {
            return "HASH_MISMATCH";
        }
        try {
            if (!IdentitySigner.verify(trustedPublicKey,
                    candidate.canonicalSha256().getBytes(StandardCharsets.US_ASCII),
                    Base64.getDecoder().decode(candidate.signature()))) {
                return "SIGNATURE_INVALID";
            }
        } catch (Exception invalidSignature) {
            return "SIGNATURE_INVALID";
        }
        return "";
    }

    private String resourceError(RuntimePackage candidate) {
        if (contentStore == null) {
            return "";
        }
        for (var resource : candidate.resources().values()) {
            try {
                byte[] bytes = contentStore.read(resource.sha256());
                if (bytes.length != resource.size()) {
                    return "RESOURCE_SIZE_MISMATCH";
                }
            } catch (ContentIntegrityException corrupted) {
                return "RESOURCE_HASH_MISMATCH";
            } catch (java.io.IOException missing) {
                return "RESOURCE_MISSING";
            }
        }
        return "";
    }

    private String dependencyError(RuntimePackage candidate, boolean requireEnabled) {
        for (var dependency : candidate.dependencies().entrySet()) {
            RuntimePackage installed = packages.get(dependency.getKey());
            if (installed == null) {
                return "DEPENDENCY_MISSING";
            }
            if (!installed.version().equals(dependency.getValue())) {
                return "DEPENDENCY_VERSION_MISMATCH";
            }
            if (requireEnabled && !installed.enabled()) {
                return "DEPENDENCY_DISABLED";
            }
        }
        return "";
    }

    private boolean introducesCycle(RuntimePackage candidate) {
        var graph = new LinkedHashMap<UUID, Set<UUID>>();
        packages.forEach((id, runtimePackage) -> graph.put(id, runtimePackage.dependencies().keySet()));
        graph.put(candidate.packageId(), candidate.dependencies().keySet());
        return reaches(candidate.packageId(), candidate.packageId(), graph, new java.util.HashSet<>(), true);
    }

    private static boolean reaches(
            UUID current,
            UUID target,
            Map<UUID, Set<UUID>> graph,
            Set<UUID> visited,
            boolean root
    ) {
        if (!root && current.equals(target)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (UUID dependency : graph.getOrDefault(current, Set.of())) {
            if (reaches(dependency, target, graph, visited, false)) {
                return true;
            }
        }
        return false;
    }

    public synchronized dev.mineagent.runtime.core.persistence.PackageLibraryHistory.Catalog catalog(UUID world,UUID owner,String search,int offset,int limit)throws Exception{return repository.packageCatalog(world,owner,search,offset,limit);}
    public synchronized List<dev.mineagent.runtime.core.persistence.PackageLibraryHistory.VersionSummary> versions(UUID id,int offset)throws Exception{return repository.packageVersions(id,offset,9);}
    public synchronized dev.mineagent.runtime.core.persistence.PackageLibraryHistory.Usage usage()throws Exception{return repository.packageLibraryUsage();}
    public record SavedVersion(RuntimePackage manifest,String payloadHash,String provenance,long observedAt,String payload){}
    public synchronized SavedVersion version(UUID id,long revision,String expectedPayloadHash)throws Exception{
        var stored=repository.packageVersion(id,revision);if(stored==null)throw new IllegalStateException("PACKAGE_VERSION_NOT_RECORDED");
        String hash=RuntimePackageCanonicalizer.sha256(stored.payload().getBytes(StandardCharsets.UTF_8));if(!hash.equals(stored.payloadHash())||!hash.equals(expectedPayloadHash))throw new IllegalStateException("PACKAGE_VERSION_SNAPSHOT_CHANGED");
        var manifest=mapper.readValue(stored.payload(),RuntimePackage.class);if(!manifest.packageId().equals(id)||manifest.revision()!=revision||!integrityError(manifest).isEmpty())throw new IllegalStateException("PACKAGE_VERSION_INVALID");
        return new SavedVersion(manifest,hash,stored.provenance(),stored.observedAt(),stored.payload());
    }
    private RuntimePackage readValidated(dev.mineagent.runtime.core.persistence.RuntimeRecord record,RuntimePackage fallback)throws Exception{
        if(record.deleted())throw new IllegalStateException("PACKAGE_QUARANTINED");var latest=read(record.payload(),fallback);
        if(!latest.packageId().equals(fallback.packageId())||latest.revision()!=record.revision()||!integrityError(latest).isEmpty()||!resourceError(latest).isEmpty()){
            packages.remove(fallback.packageId());quarantined.put(fallback.packageId(),"STALE_RECORD_INVALID");throw new IllegalStateException("PACKAGE_QUARANTINED");
        }return latest;
    }
    private RuntimePackage read(String payload, RuntimePackage fallback) throws Exception {
        return payload == null || payload.isBlank() ? fallback : mapper.readValue(payload, RuntimePackage.class);
    }

    private static RuntimePackage copy(RuntimePackage source, boolean enabled, long revision, long updatedAt) {
        return new RuntimePackage(source.packageId(), source.type(), source.name(), source.version(),
                source.activationMode(), source.dependencies(), source.permissions(), source.entrypoints(),
                source.definitions(), source.resources(), source.origin(), enabled, revision,
                source.canonicalSha256(), source.signature(), updatedAt,source.nativeCompatibility());
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
