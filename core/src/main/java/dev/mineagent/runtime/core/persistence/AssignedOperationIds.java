package dev.mineagent.runtime.core.persistence;

import java.util.*;

/** Assigns IDs to explicit small lifecycle keys. The payload is an ID, never a content digest. */
public final class AssignedOperationIds {
    public static UUID resolve(SqliteRuntimeRepository repository,UUID world,String namespace,String key,Collection<UUID> existing)throws Exception{
        Objects.requireNonNull(world);Objects.requireNonNull(existing);
        if(namespace==null||!namespace.matches("[a-z][a-z0-9_]{0,63}")||key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("ASSIGNED_OPERATION_SCOPE");
        var candidates=new HashSet<>(existing);if(candidates.size()>1)throw new IllegalStateException("ASSIGNED_OPERATION_AMBIGUOUS_LEGACY_BINDING");
        var prior=repository.get(world,namespace,key);if(prior.isPresent())return checked(prior.orElseThrow().payload(),candidates);
        UUID id=candidates.isEmpty()?UUID.randomUUID():candidates.iterator().next();
        var result=repository.compareAndSet(world,namespace,key,0,id.toString(),System.currentTimeMillis());return checked(result.record().payload(),candidates);
    }
    private static UUID checked(String payload,Set<UUID> existing){UUID value=UUID.fromString(payload);if(!existing.isEmpty()&&!existing.contains(value))throw new IllegalStateException("ASSIGNED_OPERATION_BINDING_CHANGED");return value;}
    private AssignedOperationIds(){}
}
