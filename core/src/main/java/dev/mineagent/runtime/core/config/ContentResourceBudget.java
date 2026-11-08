package dev.mineagent.runtime.core.config;

import java.util.Map;
import java.util.Set;

/** Active work and reclaimable caches only; no limit on how many items/objects have ever been authored. */
public record ContentResourceBudget(int physicsSlices, long physicsNanos, int activeRadius, long modelCacheBytes, long instanceCacheBytes) {
    public static final String PREFIX="resources.content.";
    public static final Set<String> KEYS=Set.of(PREFIX+"physicsSlices",PREFIX+"physicsMillis",PREFIX+"activeRadius",PREFIX+"modelCacheMiB",PREFIX+"instanceCacheMiB");
    public static ContentResourceBudget from(Map<String,String> values){
        return new ContentResourceBudget(number(values,"physicsSlices",256,0,Integer.MAX_VALUE),number(values,"physicsMillis",2,0,1000)*1_000_000L,
                number(values,"activeRadius",128,1,30000000),number(values,"modelCacheMiB",64,1,1048576)*1024L*1024,number(values,"instanceCacheMiB",64,1,1048576)*1024L*1024);
    }
    private static int number(Map<String,String> values,String suffix,int fallback,int min,int max){
        try{int n=Integer.parseInt(values.getOrDefault(PREFIX+suffix,Integer.toString(fallback)));if(n<min||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("CONTENT_RESOURCE_BUDGET_"+suffix);}
    }
}
