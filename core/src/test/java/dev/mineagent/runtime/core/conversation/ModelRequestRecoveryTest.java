package dev.mineagent.runtime.core.conversation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ModelRequestRecoveryTest {
    private Map<String,Object> rejection(int status,int deltas){return Map.of("httpStatus",status,"deltaCount",deltas,"providerRejected",true);}
    @Test void retriesOnlyKnownBusyRejectionsBeforeAnyOutput(){
        assertEquals(ModelRequestRecovery.Action.WAIT,ModelRequestRecovery.decide(Map.of("deltaCount",0,"providerTransportFailure",true),0,0).action());
        assertEquals(ModelRequestRecovery.Action.CONTINUE,ModelRequestRecovery.decide(Map.of("deltaCount",1,"providerTransportFailure",true),0,0).action());
        assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(Map.of("deltaCount",1,"providerTransportFailure",true),3,0).action());
        assertEquals(ModelRequestRecovery.Action.WAIT,ModelRequestRecovery.decide(rejection(429,0),0,0).action());
        for(int status:List.of(408,425,500,502,504))assertEquals(ModelRequestRecovery.Action.WAIT,ModelRequestRecovery.decide(rejection(status,0),0,0).action());
        assertEquals(4000,ModelRequestRecovery.decide(rejection(503,0),2,0).delayMillis());
        for(int status:List.of(0,400,401,403))assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(rejection(status,0),0,0).action());
        assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(rejection(503,1),0,0).action());
        assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(Map.of(),0,0).action());
        assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(rejection(503,0),3,0).action());
    }
    @Test void respectsRetryAfterAndChangesContextInsteadOfBlindReplay(){
        var value=new HashMap<>(rejection(429,0));value.put("retryAfterMillis",5000);assertEquals(5000,ModelRequestRecovery.decide(value,0,0).delayMillis());
        value.put("retryAfterMillis",121000);assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(value,0,0).action());
        value.put("contextTooLarge",true);assertEquals(ModelRequestRecovery.Action.SHRINK,ModelRequestRecovery.decide(value,0,0).action());
        assertEquals(ModelRequestRecovery.Action.FAIL,ModelRequestRecovery.decide(value,0,2).action());
    }
    @Test void reducedHistoryKeepsCurrentInputAndAuthoritySeparate(){
        var messages=List.<Map<String,Object>>of(Map.of("role","system","content","rules"),Map.of("role","user","content","[历史消息 source=first]"+"a".repeat(600)),Map.of("role","assistant","content","[历史消息 source=second]"+"b".repeat(600)),Map.of("role","user","content","<data_context source=memory>home</data_context>"),Map.of("role","user","content","current objective"));
        var plan=new ConversationContext.Plan(messages.toString(),1600,0,"READY","bytes",256,2,"summary",1,messages);var smaller=ConversationContext.reduceHistory(plan);
        assertNotSame(plan,smaller);assertEquals(messages.getFirst(),smaller.messages().getFirst());assertEquals(messages.getLast(),smaller.messages().getLast());assertTrue(smaller.messages().contains(messages.get(3)));assertFalse(smaller.messages().contains(messages.get(1)));
    }
}
