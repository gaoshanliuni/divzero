package dev.mineagent.runtime.core.conversation;

import java.util.Map;

/** Retry only explicit pre-output provider rejections; never infer rejection from a transport exception. */
public final class ModelRequestRecovery {
    public enum Action { FAIL, WAIT, SHRINK }
    public record Decision(Action action,long delayMillis) {}
    public static Decision decide(Map<String,Object> receipt,int busyRetries,int contextRepairs){
        if(!Boolean.TRUE.equals(receipt.get("providerRejected"))||number(receipt,"deltaCount",-1)!=0)return new Decision(Action.FAIL,0);
        if(Boolean.TRUE.equals(receipt.get("contextTooLarge")))return new Decision(contextRepairs<2?Action.SHRINK:Action.FAIL,0);
        long status=number(receipt,"httpStatus",0),retryAfter=number(receipt,"retryAfterMillis",0);
        if((status!=429&&status!=503)||busyRetries>=3||retryAfter>120_000)return new Decision(Action.FAIL,0);
        return new Decision(Action.WAIT,Math.max(retryAfter,1000L<<busyRetries));
    }
    private static long number(Map<String,Object> values,String key,long fallback){return values.get(key) instanceof Number n?n.longValue():fallback;}
    private ModelRequestRecovery(){}
}
