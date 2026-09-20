package dev.mineagent.runtime.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentDefinition;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PersistentAgentService implements AutoCloseable {
    private static final String NAMESPACE = "agents";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final AgentRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();
    private final Map<UUID,Long> authorityGenerations=new LinkedHashMap<>();

    private PersistentAgentService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            int maximumAgents
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.registry = new AgentRegistry(maximumAgents);
        for (var record : repository.list(worldId, NAMESPACE)) {
            PersistentAgent agent = mapper.readValue(record.payload(), PersistentAgent.class);
            registry.restore(agent.definition());
            revisions.put(agent.definition().agentId(), agent.revision());authorityGenerations.put(agent.definition().agentId(),agent.authorityGeneration());
        }
    }

    public static PersistentAgentService open(Path database, UUID worldId, int maximumAgents) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new PersistentAgentService(repository, worldId, maximumAgents);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized PersistentAgent create(String name, UUID owner, AgentMode mode) throws Exception {
        AgentDefinition definition = registry.create(name, owner, mode);
        var agent = new PersistentAgent(1, definition);
        try {
            var stored = repository.compareAndSet(worldId, NAMESPACE, definition.agentId().toString(), 0,
                    mapper.writeValueAsString(agent), System.currentTimeMillis());
            if (!stored.accepted()) throw new IllegalStateException("agent id collision");
        } catch (Exception failure) {
            registry.remove(definition.agentId(), owner, true);
            throw failure;
        }
        revisions.put(definition.agentId(), 1L);
        return agent;
    }

    public synchronized void setMaximumAgents(int maximumAgents) {
        registry.setMaximumAgents(maximumAgents);
    }

    public synchronized Optional<PersistentAgent> get(UUID agentId) {
        return registry.get(agentId).map(definition ->
                new PersistentAgent(revisions.getOrDefault(agentId, 0L), definition,authorityGenerations.getOrDefault(agentId,0L)));
    }
    public synchronized PersistentAgent createIdempotent(UUID operation,String name,UUID owner,AgentMode mode)throws Exception{
        java.util.Objects.requireNonNull(operation);java.util.Objects.requireNonNull(owner);java.util.Objects.requireNonNull(mode);
        if(name==null||name.isBlank()||name.strip().codePointCount(0,name.strip().length())>32||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("AGENT_NAME_INVALID");
        UUID id=UUID.nameUUIDFromBytes(("agent-create|"+worldId+"|"+owner+"|"+operation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fingerprint=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(mapper.writeValueAsBytes(List.of(name.strip(),owner,mode)));
        var request=repository.get(worldId,"agent_create_requests",id.toString());
        if(request.isPresent()){if(!request.get().payload().equals(fingerprint))throw new IllegalArgumentException("AGENT_OPERATION_REUSED");}
        else{
            if(repository.list(worldId,"agent_create_requests").size()>=4096)throw new IllegalStateException("AGENT_REQUEST_BUDGET");
            if(!repository.compareAndSet(worldId,"agent_create_requests",id.toString(),0,fingerprint,System.currentTimeMillis()).accepted())throw new IllegalStateException("AGENT_REQUEST_CONFLICT");
        }
        var record=repository.getIncludingDeleted(worldId,NAMESPACE,id.toString());
        if(record.isPresent()){
            if(record.get().deleted())throw new IllegalStateException("AGENT_CREATION_REMOVED");
            return get(id).orElseThrow(()->new IllegalStateException("AGENT_STATE_CONFLICT"));
        }
        var definition=registry.createWithId(id,name,owner,mode);var agent=new PersistentAgent(1,definition);
        try{if(!repository.compareAndSet(worldId,NAMESPACE,id.toString(),0,mapper.writeValueAsString(agent),System.currentTimeMillis()).accepted())throw new IllegalStateException("AGENT_STATE_CONFLICT");}
        catch(Exception failure){registry.remove(id,owner,true);throw failure;}
        revisions.put(id,1L);return agent;
    }

    public synchronized List<PersistentAgent> all() {
        return registry.all().stream().map(definition ->
                new PersistentAgent(revisions.getOrDefault(definition.agentId(), 0L), definition,authorityGenerations.getOrDefault(definition.agentId(),0L))).toList();
    }

    public synchronized AgentMutationResult rename(
            UUID agentId,
            long expectedRevision,
            UUID actor,
            boolean operator,
            String name
    ) throws Exception {
        PersistentAgent current = require(agentId);
        AgentMutationResult rejected = authorize(current, expectedRevision, actor, operator);
        if (rejected != null) {
            return rejected;
        }
        registry.rename(agentId, actor, operator, name);
        return persist(current, registry.get(agentId).orElseThrow());
    }

    public synchronized AgentMutationResult setMode(
            UUID agentId,
            long expectedRevision,
            UUID actor,
            boolean operator,
            AgentMode mode
    ) throws Exception {
        PersistentAgent current = require(agentId);
        AgentMutationResult rejected = authorize(current, expectedRevision, actor, operator);
        if (rejected != null) {
            return rejected;
        }
        registry.setMode(agentId, actor, operator, mode);
        return persist(current, registry.get(agentId).orElseThrow());
    }

    public synchronized AgentMutationResult setCollaborator(
            UUID agentId,
            long expectedRevision,
            UUID actor,
            UUID collaborator,
            boolean enabled
    ) throws Exception {
        PersistentAgent current = require(agentId);
        if (!current.definition().ownerPlayerId().equals(actor)) {
            return AgentMutationResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return AgentMutationResult.rejected(current, "STALE_REVISION");
        }
        registry.setCollaborator(agentId, actor, collaborator, enabled);
        return persist(current, registry.get(agentId).orElseThrow());
    }

    public synchronized AgentMutationResult delete(
            UUID agentId,
            long expectedRevision,
            UUID actor,
            boolean operator
    ) throws Exception {
        PersistentAgent current = require(agentId);
        AgentMutationResult rejected = authorize(current, expectedRevision, actor, operator);
        if (rejected != null) {
            return rejected;
        }
        var deleted = repository.delete(worldId, NAMESPACE, agentId.toString(),
                expectedRevision, System.currentTimeMillis());
        if (!deleted.accepted()) {
            return AgentMutationResult.rejected(current, "STALE_REVISION");
        }
        registry.remove(agentId, actor, operator);
        revisions.remove(agentId);authorityGenerations.remove(agentId);
        return AgentMutationResult.accepted(new PersistentAgent(expectedRevision + 1, current.definition(),current.authorityGeneration()));
    }

    private AgentMutationResult persist(PersistentAgent current, AgentDefinition definition) throws Exception {
        boolean changed=!current.definition().ownerPlayerId().equals(definition.ownerPlayerId())||!current.definition().collaboratorPlayerIds().equals(definition.collaboratorPlayerIds());
        var next=new PersistentAgent(current.revision()+1,definition,changed?Math.addExact(current.authorityGeneration(),1):current.authorityGeneration());
        dev.mineagent.runtime.core.persistence.RuntimeCasResult stored;
        try{stored=repository.compareAndSet(worldId,NAMESPACE,definition.agentId().toString(),current.revision(),mapper.writeValueAsString(next),System.currentTimeMillis());}
        catch(Exception failure){try{restoreBeforeFailedWrite(current);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}throw failure;}
        if(!stored.accepted()){restoreBeforeFailedWrite(current);return AgentMutationResult.rejected(current,"STALE_REVISION");}
        revisions.put(definition.agentId(),next.revision());authorityGenerations.put(definition.agentId(),next.authorityGeneration());return AgentMutationResult.accepted(next);
    }

    private void restoreBeforeFailedWrite(PersistentAgent previous){registry.remove(previous.definition().agentId(),previous.definition().ownerPlayerId(),true);registry.restore(previous.definition());}

    private AgentMutationResult authorize(
            PersistentAgent current,
            long expectedRevision,
            UUID actor,
            boolean operator
    ) {
        if (!operator && !current.definition().ownerPlayerId().equals(actor)) {
            return AgentMutationResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return AgentMutationResult.rejected(current, "STALE_REVISION");
        }
        return null;
    }

    private PersistentAgent require(UUID agentId) {
        return get(agentId).orElseThrow(() -> new IllegalArgumentException("unknown agent"));
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
