package dev.mineagent.runtime.core.conversation;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Conversation-local write barrier. Returning an error to the model is not authorization to replay it. */
public final class UncertainToolCalls {
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private final Map<String,Map<String,Object>> pending=new HashMap<>();
    private static String key(String tool,String arguments){String canonical;try{canonical=JSON.writeValueAsString(JSON.convertValue(ToolArguments.parse(tool,arguments),Object.class));}catch(Exception invalid){canonical=arguments;}try{return tool+"/"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public void observe(String tool,String arguments,Map<String,Object> result){
        if("UNKNOWN".equals(result.get("status"))||"UNKNOWN".equals(result.get("executionState")))pending.put(key(tool,arguments),ToolFailure.summary(result));
        if(tool.equals("inspect_operations")&&result.get("operation")!=null&&result.get("text") instanceof String text&&result.get("nextOffset") instanceof Number end&&end.intValue()<0)try{
            var receipt=JSON.readTree(text);if(receipt.path("executionState").asText().equals("NOT_STARTED"))pending.values().removeIf(v->Objects.toString(v.get("operation_id"),"").equals(result.get("operation").toString()));
        }catch(Exception incomplete){}
    }
    public Optional<Map<String,Object>> blocked(String tool,String arguments){var previous=pending.get(key(tool,arguments));if(previous==null)return Optional.empty();
        return Optional.of(Map.of("status","REJECTED","error","PREVIOUS_WRITE_OUTCOME_UNKNOWN","executionState","NOT_STARTED","worldModified",false,"replayAllowed",false,"operation_id",Objects.toString(previous.get("operation_id"),""),"diagnostic","The previous identical write has no confirmed outcome. This duplicate was not executed.","previousResult",previous));
    }
}
