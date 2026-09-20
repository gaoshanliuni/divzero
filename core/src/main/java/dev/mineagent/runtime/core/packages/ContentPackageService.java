package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.ContentPackage;
import dev.mineagent.runtime.api.packages.ContentPackageResult;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
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
            byte[] trustedPublicKey
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.trustedPublicKey = trustedPublicKey.clone();
        for (var record : repository.list(worldId, NAMESPACE)) {
            ContentPackage contentPackage = mapper.readValue(record.payload(), ContentPackage.class);
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
            return new ContentPackageService(repository, worldId, clock, trustedPublicKey);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

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
        String calculated = sha256(candidate.source().getBytes(StandardCharsets.UTF_8));
        if (!calculated.equals(candidate.sha256())) {
            return "HASH_MISMATCH";
        }
        boolean signatureValid;
        try {
            signatureValid = IdentitySigner.verify(trustedPublicKey,
                    candidate.sha256().getBytes(StandardCharsets.US_ASCII),
                    Base64.getDecoder().decode(candidate.signature()));
        } catch (Exception invalid) {
            signatureValid = false;
        }
        if (!signatureValid) {
            return "SIGNATURE_INVALID";
        }
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

    public static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
