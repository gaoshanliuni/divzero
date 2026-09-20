package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** A pinned data resource, not a caller identity or a grant. */
public record SharedStateTarget(UUID packageId,UUID instanceId,long packageRevision,String canonicalSha256,String namespace){
    public SharedStateTarget{Objects.requireNonNull(packageId);Objects.requireNonNull(instanceId);if(packageRevision<1||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||namespace==null)throw new IllegalArgumentException("SHARED_TARGET");if(!namespace.isEmpty())SharedJson.name(namespace);}
    public static final Set<String> FIELDS=Set.of("package_id","instance_id","package_revision","canonical_sha256","namespace");
    public static SharedStateTarget parse(JsonNode n,boolean namespaceRequired){var result=new SharedStateTarget(UUID.fromString(SharedJson.text(n,"package_id","")),UUID.fromString(SharedJson.text(n,"instance_id","")),SharedJson.integer(n,"package_revision",-1),SharedJson.text(n,"canonical_sha256",""),SharedJson.text(n,"namespace",""));if(namespaceRequired&&result.namespace.isEmpty()||!namespaceRequired&&n.has("namespace"))throw new IllegalArgumentException("SHARED_NAMESPACE_ARGUMENT");return result;}
    public Map<String,Object> wire(){var n=new LinkedHashMap<String,Object>();n.put("package_id",packageId);n.put("instance_id",instanceId);n.put("package_revision",packageRevision);n.put("canonical_sha256",canonicalSha256);if(!namespace.isEmpty())n.put("namespace",namespace);return n;}
    public SharedStateStore.Scope scope(UUID world){if(namespace.isEmpty())throw new IllegalArgumentException("SHARED_NAMESPACE_REQUIRED");return new SharedStateStore.Scope(world,packageId,instanceId,namespace);}
}
