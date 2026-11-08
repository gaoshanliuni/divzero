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
    private final Map<UUID, RuntimeInstance> instances = new LinkedHashMap<>(16,.75f,true);
    private java.util.function.LongSupplier cacheBudget=()->64L*1024*1024;
    private long cacheBytes;
    public synchronized void cacheBudget(java.util.function.LongSupplier supplier){cacheBudget=java.util.Objects.requireNonNull(supplier);trimCache();}
    public synchronized Map<String,Object> cacheObservation(){return Map.of("entries",instances.size(),"bytes",cacheBytes,"budgetBytes",cacheBudget.getAsLong());}
    private static long bytes(RuntimeInstance value){long n=512;for(var e:value.state().entrySet())n+=64L+2L*(e.getKey().length()+e.getValue().length());return n;}
    private void cache(RuntimeInstance value){var old=instances.remove(value.instanceId());if(old!=null)cacheBytes-=bytes(old);long size=bytes(value);if(size<=cacheBudget.getAsLong()){instances.put(value.instanceId(),value);cacheBytes+=size;}trimCache();}
    private void trimCache(){var entries=instances.entrySet().iterator();while(cacheBytes>cacheBudget.getAsLong()&&entries.hasNext()){cacheBytes-=bytes(entries.next().getValue());entries.remove();}}

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
        // Persistent instances are loaded on demand; old inactive content does not occupy the hot cache.

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
        var existing=get(java.util.Objects.requireNonNull(instanceId)).orElse(null);
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
        cache(instance);
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
            cache(latest);
            return RuntimeInstanceMutationResult.rejected(latest, "STALE_REVISION");
        }
        cache(next);
        return RuntimeInstanceMutationResult.accepted(next);
    }
    public synchronized RuntimeInstanceMutationResult relocate(UUID instanceId,long expectedRevision,RuntimeInstanceLocation location,Map<String,String> state)throws Exception{return replaceState(instanceId,expectedRevision,state,java.util.Objects.requireNonNull(location));}

    public synchronized Optional<RuntimeInstance> get(UUID instanceId) {
        var cached=instances.get(instanceId);if(cached!=null)return Optional.of(cached);
        try{var row=repository.get(worldId,NAMESPACE,instanceId.toString());if(row.isEmpty())return Optional.empty();var value=mapper.readValue(row.get().payload(),RuntimeInstance.class);if(!value.worldId().equals(worldId)||!value.instanceId().equals(instanceId))throw new IllegalStateException("INSTANCE_SCOPE_CHANGED");cache(value);return Optional.of(value);}catch(Exception error){throw new IllegalStateException("INSTANCE_READ_FAILED",error);}
    }

    public synchronized List<RuntimeInstance> all() {
        try{var values=new java.util.ArrayList<RuntimeInstance>();for(var row:repository.list(worldId,NAMESPACE)){var value=mapper.readValue(row.payload(),RuntimeInstance.class);if(!value.worldId().equals(worldId))throw new IllegalStateException("INSTANCE_SCOPE_CHANGED");values.add(value);}return List.copyOf(values);}catch(Exception error){throw new IllegalStateException("INSTANCE_READ_FAILED",error);}
    }

    private RuntimeInstance require(UUID instanceId) {
        RuntimeInstance instance = get(instanceId).orElse(null);
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
