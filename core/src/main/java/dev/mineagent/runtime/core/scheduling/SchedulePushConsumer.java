package dev.mineagent.runtime.core.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import java.util.*;

/** A pinned, opt-in World UI refetch target. The schedule's slots are the signal budget. */
public record SchedulePushConsumer(UUID packageId,UUID instanceId,long packageRevision,String canonicalSha256,String entryPath){
    public SchedulePushConsumer{Objects.requireNonNull(packageId);Objects.requireNonNull(instanceId);RuntimeEntrypoint.requireRelativePath(entryPath);if(packageRevision<1||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||!entryPath.startsWith("ui/")||!entryPath.endsWith(".html")||entryPath.length()>160)throw new IllegalArgumentException("SCHEDULE_PUSH_TARGET");}
    public static SchedulePushConsumer optional(JsonNode request){
        if(!request.has("push"))return null;var n=request.get("push");if(!n.isObject())throw new IllegalArgumentException("SCHEDULE_PUSH_TARGET");var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("package_id","instance_id","package_revision","canonical_sha256","entry_path"))||!n.path("package_revision").isIntegralNumber()||!n.path("package_revision").canConvertToLong())throw new IllegalArgumentException("SCHEDULE_PUSH_TARGET");
        for(var key:List.of("package_id","instance_id","canonical_sha256","entry_path"))if(!n.path(key).isTextual())throw new IllegalArgumentException("SCHEDULE_PUSH_TARGET");
        return new SchedulePushConsumer(UUID.fromString(n.get("package_id").asText()),UUID.fromString(n.get("instance_id").asText()),n.get("package_revision").longValue(),n.get("canonical_sha256").asText(),n.get("entry_path").asText());
    }
    public Map<String,Object> wire(){return new TreeMap<>(Map.of("package_id",packageId,"instance_id",instanceId,"package_revision",packageRevision,"canonical_sha256",canonicalSha256,"entry_path",entryPath));}
}
