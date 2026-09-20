package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded data only: no eval, SQL, object construction or caller-selected deserialization classes. */
final class SharedJson {
    static final long SAFE_INTEGER=9_007_199_254_740_991L;
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(32768).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private SharedJson(){}
    static JsonNode parse(String source,int bytes){
        try{if(source==null||source.getBytes(StandardCharsets.UTF_8).length>bytes)throw new IllegalArgumentException();
            JsonNode value=JSON.readTree(source);if(value==null)throw new IllegalArgumentException();return canonical(value,0);
        }catch(Exception e){throw new IllegalArgumentException("SHARED_JSON_INVALID",e);}
    }
    static JsonNode canonical(JsonNode n,int depth){
        if(n==null||depth>12)throw new IllegalArgumentException("SHARED_JSON_DEPTH");
        if(n.isObject()){ObjectNode out=JSON.createObjectNode();var keys=new ArrayList<String>();n.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys){if(key.length()>128||key.indexOf('\0')>=0)throw new IllegalArgumentException("SHARED_JSON_KEY");out.set(key,canonical(n.get(key),depth+1));}return out;}
        if(n.isArray()){if(n.size()>256)throw new IllegalArgumentException("SHARED_JSON_ARRAY");ArrayNode out=JSON.createArrayNode();for(var v:n)out.add(canonical(v,depth+1));return out;}
        if(n.isNumber()&&(!Double.isFinite(n.doubleValue())||n.isIntegralNumber()&&(!n.canConvertToLong()||Math.abs(n.decimalValue().doubleValue())>SAFE_INTEGER)))throw new IllegalArgumentException("SHARED_JSON_NUMBER");
        return n.deepCopy();
    }
    static void keys(JsonNode n,Set<String> allowed,Set<String> required){if(n==null||!n.isObject())throw new IllegalArgumentException("SHARED_OBJECT_REQUIRED");var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);if(!allowed.containsAll(keys)||!keys.containsAll(required))throw new IllegalArgumentException("SHARED_FIELDS_INVALID");}
    static String text(JsonNode n,String key,String fallback){if(!n.has(key))return fallback;if(!n.get(key).isTextual())throw new IllegalArgumentException("SHARED_STRING_REQUIRED");return n.get(key).textValue();}
    static long integer(JsonNode n,String key,long fallback){if(!n.has(key))return fallback;var v=n.get(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue() < -SAFE_INTEGER||v.longValue()>SAFE_INTEGER)throw new IllegalArgumentException("SHARED_INTEGER_REQUIRED");return v.longValue();}
    static String name(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")||value.contains(".."))throw new IllegalArgumentException("SHARED_NAME_INVALID");return value;}
}
