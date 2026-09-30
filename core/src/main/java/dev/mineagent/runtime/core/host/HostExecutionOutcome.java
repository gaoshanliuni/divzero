package dev.mineagent.runtime.core.host;

import java.util.*;

/** Distinguishes environment preparation, requested process launch, and an uncertain prior operation. */
public final class HostExecutionOutcome {
    public static Map<String,Object> describe(UUID operation,Map<String,Object> original,boolean launched,String phase,String cancellation){
        var value=new LinkedHashMap<String,Object>(original);String error=Objects.toString(value.getOrDefault("error",""));
        String status=Objects.toString(value.getOrDefault("status","UNKNOWN"));
        if(!cancellation.isBlank()){
            value.put("cancellationReason",cancellation);
            if(error.equals("HOST_CONTEXT_CHANGED")||error.equals("HOST_EXECUTION_INTERRUPTED")||status.equals("CANCELLED"))value.put("error",cancellation);
        }
        value.put("operation",operation.toString());value.put("phase",phase);
        // A duplicate refusal says nothing about what the original operation did.
        boolean unknown=error.equals("HOST_OPERATION_NOT_REPLAYABLE")||status.equals("UNKNOWN");
        value.put("executionState",unknown?"UNKNOWN":!launched?"NOT_STARTED":status.equals("EXECUTED")?"COMPLETED":"STARTED");
        if(!unknown||launched)value.put("operationProcessStarted",launched);
        value.put("environmentMayHaveChanged",!Set.of("VALIDATING","AWAITING_APPROVAL").contains(phase));
        value.putIfAbsent("sideEffectsMayPersist",launched&&!status.equals("EXECUTED")||unknown);
        return Collections.unmodifiableMap(value);
    }
    private HostExecutionOutcome(){}
}
