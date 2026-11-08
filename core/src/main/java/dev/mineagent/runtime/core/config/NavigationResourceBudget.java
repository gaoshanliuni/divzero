package dev.mineagent.runtime.core.config;

import java.util.Map;
import java.util.Set;

public record NavigationResourceBudget(int tickNodes,int sliceNodes,int searchNodes,long minNanos,long maxNanos,long targetTickNanos) {
    public static final String PREFIX="resources.navigation.";
    public static final Set<String> KEYS=Set.of(PREFIX+"tickNodes",PREFIX+"sliceNodes",PREFIX+"searchNodes",PREFIX+"minMillis",PREFIX+"maxMillis",PREFIX+"targetMillis");
    public static NavigationResourceBudget from(Map<String,String> values){return new NavigationResourceBudget(value(values,"tickNodes",4096),value(values,"sliceNodes",96),value(values,"searchNodes",131072),value(values,"minMillis",1)*1_000_000L,value(values,"maxMillis",8)*1_000_000L,value(values,"targetMillis",45)*1_000_000L);}
    public NavigationResourceBudget {if(tickNodes<1||sliceNodes<1||searchNodes<1||minNanos<0||maxNanos<minNanos||targetTickNanos<1)throw new IllegalArgumentException("NAVIGATION_RESOURCE_BUDGET_INVALID");}
    public long nanosFor(long observedTickNanos){return Math.min(maxNanos,Math.max(minNanos,(targetTickNanos-Math.max(0,observedTickNanos))/4));}
    private static int value(Map<String,String> values,String name,int fallback){try{int n=Integer.parseInt(values.getOrDefault(PREFIX+name,Integer.toString(fallback)));if(n<0)throw new NumberFormatException();return n;}catch(NumberFormatException invalid){throw new IllegalArgumentException("NAVIGATION_RESOURCE_BUDGET_"+name);}}
}
