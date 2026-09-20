package dev.mineagent.runtime.core.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** A pinned schedule callback in an already approved package; never caller-supplied source. */
public record ScriptScheduleConsumer(UUID packageId,UUID instanceId,long packageRevision,String canonicalSha256,String handler){
    public ScriptScheduleConsumer{Objects.requireNonNull(packageId);Objects.requireNonNull(instanceId);if(packageRevision<1||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||handler==null||!handler.matches("runtime\\.schedule\\.[A-Za-z][A-Za-z0-9_.:-]{0,63}"))throw new IllegalArgumentException("SCHEDULE_SCRIPT_BINDING");}
    public static ScriptScheduleConsumer optional(JsonNode request){
        if(!request.has("script"))return null;var value=request.get("script");if(!value.isObject())throw new IllegalArgumentException("SCHEDULE_SCRIPT_BINDING");var keys=new HashSet<String>();value.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("package_id","instance_id","package_revision","canonical_sha256","handler"))||!value.path("package_revision").isIntegralNumber()||!value.path("package_revision").canConvertToLong())throw new IllegalArgumentException("SCHEDULE_SCRIPT_BINDING");
        for(var key:List.of("package_id","instance_id","canonical_sha256","handler"))if(!value.path(key).isTextual())throw new IllegalArgumentException("SCHEDULE_SCRIPT_BINDING");
        return new ScriptScheduleConsumer(UUID.fromString(value.get("package_id").asText()),UUID.fromString(value.get("instance_id").asText()),value.get("package_revision").longValue(),value.get("canonical_sha256").asText(),value.get("handler").asText());
    }
    public Map<String,Object> wire(){return new TreeMap<>(Map.of("package_id",packageId,"instance_id",instanceId,"package_revision",packageRevision,"canonical_sha256",canonicalSha256,"handler",handler));}
}
