package dev.mineagent.runtime.core.conversation;

import java.util.Map;

/** Model transport recovery never replays tools; partial output is retained as data for a continuation. */
public final class ModelRequestRecovery {
    public enum Action { FAIL, WAIT, SHRINK, CONTINUE }
    public record Decision(Action action,long delayMillis) {}
    public static Decision decide(Map<String,Object> receipt,int busyRetries,int contextRepairs){
        if(number(receipt,"deltaCount",-1)>0&&Boolean.TRUE.equals(receipt.get("providerTransportFailure")))return new Decision(busyRetries<3?Action.CONTINUE:Action.FAIL,1000L<<Math.min(busyRetries,3));
        if(number(receipt,"deltaCount",-1)!=0)return new Decision(Action.FAIL,0);
        if(Boolean.TRUE.equals(receipt.get("providerTransportFailure")))return new Decision(busyRetries<3?Action.WAIT:Action.FAIL,1000L<<Math.min(busyRetries,3));
        if(!Boolean.TRUE.equals(receipt.get("providerRejected")))return new Decision(Action.FAIL,0);
        if(Boolean.TRUE.equals(receipt.get("contextTooLarge")))return new Decision(contextRepairs<2?Action.SHRINK:Action.FAIL,0);
        long status=number(receipt,"httpStatus",0),retryAfter=number(receipt,"retryAfterMillis",0);
        if(!java.util.Set.of(408L,425L,429L,500L,502L,503L,504L).contains(status)||busyRetries>=3||retryAfter>120_000)return new Decision(Action.FAIL,0);
        return new Decision(Action.WAIT,Math.max(retryAfter,1000L<<busyRetries));
    }
    private static long number(Map<String,Object> values,String key,long fallback){return values.get(key) instanceof Number n?n.longValue():fallback;}
    private ModelRequestRecovery(){}
}
