package dev.mineagent.runtime.core.persistence;

/** Exact, private canonical request text for idempotency; never a content digest or a model-visible ID. */
public final class ExactRequestSnapshot {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public static String encode(Object value)throws java.io.IOException{
        String text=JSON.writeValueAsString(value);if(text.length()>262144)throw new IllegalArgumentException("OPERATION_SNAPSHOT_SIZE");return "exact-v2:"+text;
    }
    public static boolean legacy(String value){return value!=null&&value.matches("[a-fA-F0-9]{64}");}
    private ExactRequestSnapshot(){}
}
