package dev.mineagent.runtime.core.events;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pinned approved handler, never caller-supplied executable code. */
public record ScriptEventConsumer(UUID packageId,UUID instanceId,long packageRevision,String canonicalSha256,String handler,int maxRuns){
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8192).maxNumberLength(64).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ScriptEventConsumer{Objects.requireNonNull(packageId);Objects.requireNonNull(instanceId);
        if(packageRevision<1||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||handler==null||!handler.matches("runtime\\.event\\.[A-Za-z][A-Za-z0-9_.:-]{0,63}")||maxRuns<1||maxRuns>1024)throw new IllegalArgumentException("SCRIPT_CONSUMER_BINDING");}
    public static ScriptEventConsumer optional(JsonNode request){
        if(!request.has("script"))return null;var value=request.get("script");var expected=Set.of("package_id","instance_id","package_revision","canonical_sha256","handler","max_runs");
        if(!value.isObject())throw new IllegalArgumentException("SCRIPT_CONSUMER_OBJECT");var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);if(!fields.equals(expected))throw new IllegalArgumentException("SCRIPT_CONSUMER_FIELDS");
        for(String field:Set.of("package_id","instance_id","canonical_sha256","handler"))if(!value.get(field).isTextual())throw new IllegalArgumentException("SCRIPT_CONSUMER_TEXT");
        if(!value.get("package_revision").isIntegralNumber()||!value.get("package_revision").canConvertToLong()||!value.get("max_runs").isIntegralNumber()||!value.get("max_runs").canConvertToInt())throw new IllegalArgumentException("SCRIPT_CONSUMER_NUMBER");
        return new ScriptEventConsumer(UUID.fromString(value.get("package_id").textValue()),UUID.fromString(value.get("instance_id").textValue()),value.get("package_revision").longValue(),value.get("canonical_sha256").textValue(),value.get("handler").textValue(),value.get("max_runs").intValue());
    }
    public Map<String,Object> wire(){var result=new TreeMap<String,Object>();result.put("package_id",packageId);result.put("instance_id",instanceId);result.put("package_revision",packageRevision);result.put("canonical_sha256",canonicalSha256);result.put("handler",handler);result.put("max_runs",maxRuns);return result;}
    public static String normalizeResult(String source){try{
        if(source==null||source.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException();var value=JSON.readTree(source);if(value==null)throw new IllegalArgumentException();
        var pending=new ArrayDeque<JsonNode>();pending.add(value);int count=0;while(!pending.isEmpty()){var node=pending.removeFirst();if(++count>2048||node.isNumber()&&!Double.isFinite(node.doubleValue()))throw new IllegalArgumentException();node.elements().forEachRemaining(pending::addLast);}
        String encoded=JSON.writeValueAsString(value);if(encoded.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException();return encoded;
    }catch(Exception invalid){throw new IllegalArgumentException("SCRIPT_RESULT_JSON_INVALID",invalid);}}
}
