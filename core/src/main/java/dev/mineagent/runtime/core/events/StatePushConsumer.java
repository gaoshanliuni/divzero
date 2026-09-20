package dev.mineagent.runtime.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import java.util.*;

/** An owner-selected World UI refetch destination. No original event payload or arbitrary client script is sent. */
public record StatePushConsumer(UUID packageId,UUID instanceId,long packageRevision,String canonicalSha256,String entryPath,int maxSignals){
    public StatePushConsumer{Objects.requireNonNull(packageId);Objects.requireNonNull(instanceId);RuntimeEntrypoint.requireRelativePath(entryPath);
        if(packageRevision<1||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||!entryPath.startsWith("ui/")||!entryPath.endsWith(".html")||entryPath.length()>160||maxSignals<1||maxSignals>4096)throw new IllegalArgumentException("STATE_PUSH_TARGET");}
    public static StatePushConsumer optional(JsonNode request){if(!request.has("push"))return null;var node=request.get("push");if(!node.isObject())throw new IllegalArgumentException("STATE_PUSH_OBJECT");var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("package_id","instance_id","package_revision","canonical_sha256","entry_path","max_signals")))throw new IllegalArgumentException("STATE_PUSH_FIELDS");
        for(String key:Set.of("package_id","instance_id","canonical_sha256","entry_path"))if(!node.get(key).isTextual())throw new IllegalArgumentException("STATE_PUSH_TEXT");
        if(!node.get("package_revision").isIntegralNumber()||!node.get("package_revision").canConvertToLong()||!node.get("max_signals").isIntegralNumber()||!node.get("max_signals").canConvertToInt())throw new IllegalArgumentException("STATE_PUSH_NUMBER");
        return new StatePushConsumer(UUID.fromString(node.get("package_id").asText()),UUID.fromString(node.get("instance_id").asText()),node.get("package_revision").longValue(),node.get("canonical_sha256").asText(),node.get("entry_path").asText(),node.get("max_signals").intValue());}
    public Map<String,Object> wire(){var map=new TreeMap<String,Object>();map.put("package_id",packageId);map.put("instance_id",instanceId);map.put("package_revision",packageRevision);map.put("canonical_sha256",canonicalSha256);map.put("entry_path",entryPath);map.put("max_signals",maxSignals);return map;}
}
