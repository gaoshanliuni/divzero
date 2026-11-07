package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.ContentPackage;
import dev.mineagent.runtime.api.packages.ContentPackageResult;
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
import java.util.UUID;

public final class ContentPackageService implements AutoCloseable {
    private static final String NAMESPACE = "content_packages";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final byte[] trustedPublicKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, ContentPackage> packages = new LinkedHashMap<>();

    private ContentPackageService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            byte[] trustedPublicKey,IdentitySigner signer
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.trustedPublicKey = trustedPublicKey.clone();
        for (var record : repository.list(worldId, NAMESPACE)) {
            ContentPackage contentPackage = mapper.readValue(record.payload(), ContentPackage.class);
            byte[] proof=Base64.getDecoder().decode(contentPackage.signature());
            if(!dev.mineagent.runtime.core.crypto.SourceSignature.modern(proof)&&signer!=null){
                if(!IdentitySigner.verify(trustedPublicKey,contentPackage.sha256().getBytes(StandardCharsets.US_ASCII),proof))throw new SecurityException("LEGACY_SOURCE_SIGNATURE_INVALID");
                String key=contentPackage.packageId()+"_"+contentPackage.revision();var old=repository.get(worldId,"legacy_source_signatures_v2",key);
                if(old.isPresent()){var receipt=mapper.readTree(old.get().payload());if(!receipt.path("source").asText().equals(contentPackage.source())||!receipt.path("resourceId").asText().equals(contentPackage.sha256()))throw new SecurityException("LEGACY_SOURCE_MIGRATION_CHANGED");contentPackage=contentPackage.withSignature(receipt.path("signature").asText());}
                else{String signature=Base64.getEncoder().encodeToString(dev.mineagent.runtime.core.crypto.SourceSignature.sign(signer,contentPackage.sha256(),contentPackage.source()));var saved=repository.compareAndSet(worldId,"legacy_source_signatures_v2",key,0,mapper.writeValueAsString(Map.of("source",contentPackage.source(),"resourceId",contentPackage.sha256(),"signature",signature)),clock.millis());if(!saved.accepted())throw new IllegalStateException("LEGACY_SOURCE_MIGRATION_BUSY");contentPackage=contentPackage.withSignature(signature);}
            }
            packages.put(contentPackage.packageId(), contentPackage);
        }
    }

    public static ContentPackageService open(
            Path database,
            UUID worldId,
            Clock clock,
            byte[] trustedPublicKey
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new ContentPackageService(repository, worldId, clock, trustedPublicKey,null);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public static ContentPackageService open(Path database,UUID world,Clock clock,IdentitySigner signer)throws Exception{var repository=new SqliteRuntimeRepository(database);try{return new ContentPackageService(repository,world,clock,signer.publicKeyEncoded(),signer);}catch(Exception failure){repository.close();throw failure;}}
    public synchronized ContentPackageResult install(ContentPackage candidate) throws Exception {
        validate(candidate);
        if (packages.containsKey(candidate.packageId())) {
            return ContentPackageResult.rejected(packages.get(candidate.packageId()), "PACKAGE_EXISTS");
        }
        String error = integrityError(candidate);
        if (!error.isEmpty()) {
            return ContentPackageResult.rejected(candidate, error);
        }
        ContentPackage storedPackage = copy(candidate, candidate.enabled(), 1, clock.millis());
        var stored = repository.compareAndSet(worldId, NAMESPACE, candidate.packageId().toString(), 0,
                mapper.writeValueAsString(storedPackage), storedPackage.updatedAtEpochMillis());
        if (!stored.accepted()) {
            return ContentPackageResult.rejected(candidate, "PACKAGE_EXISTS");
        }
        packages.put(candidate.packageId(), storedPackage);
        return ContentPackageResult.accepted(storedPackage);
    }

    public synchronized ContentPackageResult upgrade(ContentPackage candidate, long expectedRevision)
            throws Exception {
        validate(candidate);
        ContentPackage current = packages.get(candidate.packageId());
        if (current == null) {
            return ContentPackageResult.rejected(candidate, "PACKAGE_MISSING");
        }
        if (current.revision() != expectedRevision) {
            return ContentPackageResult.rejected(current, "STALE_REVISION");
        }
        String error = integrityError(candidate);
        if (!error.isEmpty()) {
            return ContentPackageResult.rejected(current, error);
        }
        if (!current.version().equals(candidate.version()) && packages.values().stream()
                .anyMatch(other -> other.enabled() && !other.packageId().equals(current.packageId())
                        && current.version().equals(other.dependencies().get(current.packageId())))) {
            return ContentPackageResult.rejected(current, "DEPENDENT_VERSION_CONFLICT");
        }
        ContentPackage next = copy(candidate, candidate.enabled(), current.revision() + 1, clock.millis());
        var saved = repository.compareAndSet(worldId, NAMESPACE, current.packageId().toString(),
                current.revision(), mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!saved.accepted()) {
            return ContentPackageResult.rejected(current, "STALE_REVISION");
        }
        packages.put(next.packageId(), next);
        return ContentPackageResult.accepted(next);
    }

    public synchronized ContentPackageResult setEnabled(UUID packageId, long expectedRevision, boolean enabled)
            throws Exception {
        ContentPackage current = packages.get(packageId);
        if (current == null) {
            throw new IllegalArgumentException("unknown package");
        }
        if (current.revision() != expectedRevision) {
            return ContentPackageResult.rejected(current, "STALE_REVISION");
        }
        if (!enabled && packages.values().stream().anyMatch(contentPackage -> contentPackage.enabled()
                && contentPackage.dependencies().containsKey(packageId))) {
            return ContentPackageResult.rejected(current, "PACKAGE_REQUIRED");
        }
        ContentPackage next = copy(current, enabled, current.revision() + 1, clock.millis());
        var saved = repository.compareAndSet(worldId, NAMESPACE, packageId.toString(), current.revision(),
                mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!saved.accepted()) {
            return ContentPackageResult.rejected(current, "STALE_REVISION");
        }
        packages.put(packageId, next);
        return ContentPackageResult.accepted(next);
    }

    public synchronized Optional<ContentPackage> get(UUID packageId) {
        return Optional.ofNullable(packages.get(packageId));
    }

    public synchronized List<ContentPackage> all() {
        return List.copyOf(packages.values());
    }

    private static ContentPackage copy(ContentPackage contentPackage, boolean enabled, long revision, long updatedAt) {
        return new ContentPackage(contentPackage.packageId(), contentPackage.name(), contentPackage.version(),
                contentPackage.activationMode(), contentPackage.dependencies(), contentPackage.permissions(),
                contentPackage.source(), contentPackage.sha256(), contentPackage.signature(), enabled, revision, updatedAt);
    }

    private static void validate(ContentPackage contentPackage) {
        if (contentPackage == null || contentPackage.name().isBlank() || contentPackage.name().length() > 128
                || !contentPackage.version().matches("[0-9A-Za-z_.+-]{1,64}")
                || contentPackage.source().isBlank() || contentPackage.source().length() > 16_000
                || !contentPackage.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid content package");
        }
    }

    private String integrityError(ContentPackage candidate) throws Exception {
        try{if(!dev.mineagent.runtime.core.crypto.SourceSignature.verify((bytes,proof)->IdentitySigner.verify(trustedPublicKey,bytes,proof),candidate.sha256(),candidate.source(),Base64.getDecoder().decode(candidate.signature())))return "SIGNATURE_INVALID";}catch(Exception invalid){return "SIGNATURE_INVALID";}
        for (var dependency : candidate.dependencies().entrySet()) {
            ContentPackage installed = packages.get(dependency.getKey());
            if (installed == null) {
                return "DEPENDENCY_MISSING";
            }
            if (!installed.version().equals(dependency.getValue())) {
                return "DEPENDENCY_VERSION_MISMATCH";
            }
        }
        return "";
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
