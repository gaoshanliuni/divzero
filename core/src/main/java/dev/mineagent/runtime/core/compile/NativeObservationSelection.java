package dev.mineagent.runtime.core.compile;
import com.fasterxml.jackson.databind.*;
import java.util.*;
/** Exact persisted Native member/method-body receipt operations selected for one generation request. */
public record NativeObservationSelection(List<UUID> operations) {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public NativeObservationSelection {operations=List.copyOf(operations);if(operations.isEmpty()||operations.size()>16||operations.stream().distinct().count()!=operations.size())throw new IllegalArgumentException("NATIVE_OBSERVATION_SELECTION");}
    public static NativeObservationSelection parseArray(JsonNode node){try{if(node==null||!node.isArray()||node.isEmpty()||node.size()>16)throw new IllegalArgumentException();var values=new ArrayList<UUID>();for(var value:node){if(!value.isTextual())throw new IllegalArgumentException();values.add(UUID.fromString(value.textValue()));}return new NativeObservationSelection(values);}catch(Exception e){throw new IllegalArgumentException("NATIVE_OBSERVATION_SELECTION",e);}}
    public static NativeObservationSelection parseCanonical(String value){try{return parseArray(JSON.readTree(value));}catch(Exception e){throw new IllegalArgumentException("NATIVE_OBSERVATION_SELECTION",e);}}
    public String canonical(){try{return JSON.writeValueAsString(operations.stream().map(UUID::toString).toList());}catch(Exception e){throw new IllegalStateException("NATIVE_OBSERVATION_SELECTION",e);}}
}
