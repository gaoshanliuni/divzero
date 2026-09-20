package dev.mineagent.runtime.api.directory;
import java.util.*;
/** Discovery identity, not an operation grant. Generation distinguishes reconnect/reload/replacement. */
public record ObjectRef(UUID worldId,Kind kind,String id,UUID generation,long revision) {
    public enum Kind { PLAYER, TEAM, DIMENSION, ENTITY, INSTANCE }
    public ObjectRef {
        Objects.requireNonNull(worldId);Objects.requireNonNull(kind);Objects.requireNonNull(generation);
        if(id==null||id.isBlank()||id.length()>128||id.chars().anyMatch(Character::isISOControl)||revision<0)throw new IllegalArgumentException("OBJECT_REF_INVALID");
        if(Set.of(Kind.PLAYER,Kind.ENTITY,Kind.INSTANCE).contains(kind))id=UUID.fromString(id).toString();
        if(kind==Kind.DIMENSION&&!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("OBJECT_REF_INVALID");
    }
}
