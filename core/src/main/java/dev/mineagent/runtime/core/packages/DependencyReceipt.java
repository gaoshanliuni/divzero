package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Exact dependency version metadata, not a file digest or an authorization token. */
public final class DependencyReceipt {
    private static final String PREFIX="dependencies-v2:";
    public static final int MAX_CHARACTERS=512*1024;
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private DependencyReceipt(){}

    public static String encode(Object graph){
        try{
            String value=PREFIX+JSON.writeValueAsString(graph);
            if(value.length()>MAX_CHARACTERS)throw new IllegalArgumentException("DEPENDENCY_RECEIPT_LIMIT");
            return value;
        }catch(java.io.IOException error){throw new IllegalArgumentException("DEPENDENCY_RECEIPT_METADATA",error);}
    }

    /** Historical opaque receipts remain readable but cannot equal a newly observed graph. */
    public static boolean readable(String value){
        return value!=null&&(value.matches("[a-f0-9]{64}")||value.startsWith(PREFIX)&&value.length()<=MAX_CHARACTERS);
    }
    public static boolean optional(String value){return "".equals(value)||readable(value);}
}
