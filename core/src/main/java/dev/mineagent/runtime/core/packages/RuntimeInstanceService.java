package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeInstance;
import dev.mineagent.runtime.api.packages.RuntimeInstanceLocation;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RuntimeInstanceService implements AutoCloseable {
    private static final String NAMESPACE = "runtime_instances_v2";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final RuntimePackageLibrary library;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, RuntimeInstance> instances = new LinkedHashMap<>();

    private RuntimeInstanceService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            RuntimePackageLibrary library
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.library = library;
        for (var record : repository.list(worldId, NAMESPACE)) {
            RuntimeInstance instance = mapper.readValue(record.payload(), RuntimeInstance.class);
            if (!instance.worldId().equals(worldId)) {
                throw new IllegalStateException("runtime instance world mismatch");
            }
            instances.put(instance.instanceId(), instance);
        }
    }

    public static RuntimeInstanceService open(
            Path database,
            UUID worldId,
            Clock clock,
            RuntimePackageLibrary library
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new RuntimeInstanceService(repository, worldId, clock, library);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized RuntimeInstance create(
            UUID packageId,
            UUID definitionId,
            RuntimeInstanceLocation location,
            Map<String, String> state
    ) throws Exception {
        return createIfAbsent(UUID.randomUUID(),packageId,definitionId,location,state);
    }
    /** Stable identity supplied by the persisted activation intent. Existing live state is never overwritten. */
    public synchronized RuntimeInstance createIfAbsent(UUID instanceId,UUID packageId,UUID definitionId,RuntimeInstanceLocation location,Map<String,String> state)throws Exception{
        var existing=instances.get(java.util.Objects.requireNonNull(instanceId));
        if(existing!=null){
            if(!existing.packageId().equals(packageId)||!existing.definitionId().equals(definitionId)||!existing.location().equals(location))throw new IllegalArgumentException("INSTANCE_ID_REUSED");
            return existing;
        }
        RuntimePackage runtimePackage = library.get(packageId)
                .orElseThrow(() -> new IllegalArgumentException("unknown runtime package"));
        if (!runtimePackage.enabled()) {
            throw new IllegalStateException("runtime package is disabled");
        }
        var definition = runtimePackage.definitions().get(definitionId);
        if (definition == null) {
            throw new IllegalArgumentException("unknown runtime definition");
        }
        long now = clock.millis();
        RuntimeInstance instance = new RuntimeInstance(instanceId, worldId, packageId, definitionId,
                definition.revision(), location, state, definition.stateSchemaVersion(), 1, now);
        var saved = repository.compareAndSet(worldId, NAMESPACE, instance.instanceId().toString(), 0,
                mapper.writeValueAsString(instance), now);
        if (!saved.accepted()) {
            throw new IllegalStateException("runtime instance id collision");
        }
        instances.put(instance.instanceId(), instance);
        return instance;
    }

    public synchronized RuntimeInstanceMutationResult updateState(
            UUID instanceId,
            long expectedRevision,
            Map<String, String> state
    ) throws Exception {
        return replaceState(instanceId,expectedRevision,state,null);
    }
    private RuntimeInstanceMutationResult replaceState(UUID instanceId,long expectedRevision,Map<String,String> state,RuntimeInstanceLocation location)throws Exception{
        RuntimeInstance current = require(instanceId);
        if (current.revision() != expectedRevision) {
            return RuntimeInstanceMutationResult.rejected(current, "STALE_REVISION");
        }
        RuntimePackage runtimePackage = library.get(current.packageId()).orElse(null);
        if (runtimePackage == null || !runtimePackage.enabled()) {
            return RuntimeInstanceMutationResult.rejected(current, "PACKAGE_UNAVAILABLE");
        }
        var definition = runtimePackage.definitions().get(current.definitionId());
        if (definition == null || definition.revision() != current.definitionRevision()) {
            return RuntimeInstanceMutationResult.rejected(current, "DEFINITION_REVISION_CHANGED");
        }
        if(location==null)location=current.location();
        if(!current.location().dimension().equals(location.dimension())||current.location().yaw()!=location.yaw()||current.location().pitch()!=location.pitch())throw new IllegalArgumentException("INSTANCE_MOVE_CONTEXT");
        RuntimeInstance next = new RuntimeInstance(current.instanceId(), current.worldId(), current.packageId(),
                current.definitionId(), current.definitionRevision(), location, state,
                current.stateSchemaVersion(), current.revision() + 1, clock.millis());
        var saved = repository.compareAndSet(worldId, NAMESPACE, instanceId.toString(), current.revision(),
                mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!saved.accepted()) {
            RuntimeInstance latest = mapper.readValue(saved.record().payload(), RuntimeInstance.class);
            instances.put(instanceId, latest);
            return RuntimeInstanceMutationResult.rejected(latest, "STALE_REVISION");
        }
        instances.put(instanceId, next);
        return RuntimeInstanceMutationResult.accepted(next);
    }
    public synchronized RuntimeInstanceMutationResult relocate(UUID instanceId,long expectedRevision,RuntimeInstanceLocation location,Map<String,String> state)throws Exception{return replaceState(instanceId,expectedRevision,state,java.util.Objects.requireNonNull(location));}

    public synchronized Optional<RuntimeInstance> get(UUID instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    public synchronized List<RuntimeInstance> all() {
        return List.copyOf(instances.values());
    }

    private RuntimeInstance require(UUID instanceId) {
        RuntimeInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("unknown runtime instance");
        }
        return instance;
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
