package dev.mineagent.runtime.core.config;

import java.util.Map;
import java.util.Set;

/** Resident counts are monitoring thresholds, never lifetime admission limits. */
public record ScriptResourceBudget(int packages, int modules, int handlers, int timers, int resources,
                                   int callbacks, long dispatchNanos, int pendingCallbacks) {
    public static final String PREFIX = "resources.scripts.";
    public static final Set<String> KEYS = Set.of(PREFIX+"packages", PREFIX+"modules", PREFIX+"handlers",
            PREFIX+"timers", PREFIX+"resources", PREFIX+"callbacks", PREFIX+"dispatchMillis", PREFIX+"pendingCallbacks");
    public static ScriptResourceBudget from(Map<String,String> values) {
        return new ScriptResourceBudget(value(values,"packages",128), value(values,"modules",64),
                value(values,"handlers",128), value(values,"timers",128), value(values,"resources",256),
                value(values,"callbacks",256), value(values,"dispatchMillis",5)*1_000_000L,
                value(values,"pendingCallbacks",65536));
    }
    public ScriptResourceBudget {
        if (packages<1 || modules<1 || handlers<1 || timers<1 || resources<1 || callbacks<0
                || dispatchNanos<0 || pendingCallbacks<1) throw new IllegalArgumentException("SCRIPT_RESOURCE_BUDGET_INVALID");
    }
    private static int value(Map<String,String> values,String name,int fallback) {
        try {
            int result=Integer.parseInt(values.getOrDefault(PREFIX+name,Integer.toString(fallback)));
            if(result<0 || result==0 && !Set.of("callbacks","dispatchMillis").contains(name))throw new NumberFormatException();
            return result;
        } catch(NumberFormatException invalid) { throw new IllegalArgumentException("SCRIPT_RESOURCE_BUDGET_"+name); }
    }
}
