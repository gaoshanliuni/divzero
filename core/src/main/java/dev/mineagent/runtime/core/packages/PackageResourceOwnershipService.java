package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeResourceRef;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PackageResourceOwnershipService implements AutoCloseable {
    private static final UUID GLOBAL_LIBRARY_ID = RuntimePackageLibrary.GLOBAL_LIBRARY_ID;
    private static final String NAMESPACE = "runtime_resource_owners";
    private static final TypeReference<Map<UUID, Integer>> OWNER_COUNTS = new TypeReference<>() { };

    private final SqliteRuntimeRepository repository;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<UUID, Integer>> ownersByHash = new LinkedHashMap<>();
    private final Map<String, Long> revisionsByHash = new HashMap<>();

    private PackageResourceOwnershipService(SqliteRuntimeRepository repository, Clock clock) throws Exception {
        this.repository = repository;
        this.clock = clock;
        for (var record : repository.list(GLOBAL_LIBRARY_ID, NAMESPACE)) {
            ownersByHash.put(record.recordId(), new LinkedHashMap<>(mapper.readValue(record.payload(), OWNER_COUNTS)));
            revisionsByHash.put(record.recordId(), record.revision());
        }
    }

    public static PackageResourceOwnershipService open(Path database, Clock clock) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new PackageResourceOwnershipService(repository, clock);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized void acquire(UUID packageId, Collection<RuntimeResourceRef> resources) throws Exception {
        if (packageId == null || resources == null) {
            throw new IllegalArgumentException("package and resources are required");
        }
        var desired = new HashMap<String, Integer>();
        resources.forEach(resource -> desired.merge(resource.sha256(), 1, Integer::sum));
        var touched = new java.util.HashSet<String>(desired.keySet());
        ownersByHash.forEach((hash, owners) -> {
            if (owners.containsKey(packageId)) {
                touched.add(hash);
            }
        });
        for (String hash : touched) {
            Map<UUID, Integer> owners = new LinkedHashMap<>(ownersByHash.getOrDefault(hash, Map.of()));
            Integer count = desired.get(hash);
            if (count == null) {
                owners.remove(packageId);
            } else {
                owners.put(packageId, count);
            }
            persist(hash, owners);
        }
    }

    public synchronized void release(UUID packageId) throws Exception {
        if (packageId == null) {
            throw new IllegalArgumentException("package id is required");
        }
        for (String hash : ownersByHash.keySet().stream().toList()) {
            Map<UUID, Integer> owners = new LinkedHashMap<>(ownersByHash.get(hash));
            if (owners.remove(packageId) != null) {
                persist(hash, owners);
            }
        }
    }

    public synchronized int referenceCount(String sha256) {
        return ownersByHash.getOrDefault(sha256, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized Set<UUID> owners(String sha256) {
        return Set.copyOf(ownersByHash.getOrDefault(sha256, Map.of()).keySet());
    }

    private void persist(String hash, Map<UUID, Integer> owners) throws Exception {
        long revision = revisionsByHash.getOrDefault(hash, 0L);
        var saved = repository.compareAndSet(GLOBAL_LIBRARY_ID, NAMESPACE, hash, revision,
                mapper.writeValueAsString(owners), clock.millis());
        if (!saved.accepted()) {
            throw new IllegalStateException("resource ownership CAS conflict");
        }
        ownersByHash.put(hash, new LinkedHashMap<>(owners));
        revisionsByHash.put(hash, saved.record().revision());
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
