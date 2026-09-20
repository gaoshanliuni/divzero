package dev.mineagent.runtime.core.feedback;
import dev.mineagent.runtime.core.shared.SharedStateStore;
import java.util.Optional;

/** A read-only projection. Execution state is not promoted and no handler/transaction may run here. */
public record FeedbackTransactionOutcome(String status,boolean verified,String executionState,String planSha256,
        SharedStateStore.Receipt receipt,boolean replayAllowed,boolean currentValuesVerified,boolean arbitraryNativeEffectsVerified){
    @FunctionalInterface public interface Reader{Optional<SharedStateStore.Receipt> readExact()throws Exception;}
    public static FeedbackTransactionOutcome inspect(UiFeedbackStore.Item item,Reader reader){
        String hash=item.dataPlan()==null?"":item.dataPlan().sha256();String status="UNAVAILABLE";SharedStateStore.Receipt receipt=null;
        if(!item.scope().policy().mode().equals("DETERMINISTIC"))status="NOT_APPLICABLE";
        else if(item.dataPlan()==null)status="NO_PLAN_RECORDED";
        else try{item.scope().dataBinding().validate(item.dataPlan().transaction());var found=reader.readExact();if(found.isEmpty())status="NOT_RECORDED";else{var actual=found.get();if(!java.util.Set.of("APPLIED","CONFLICT").contains(actual.status())||actual.revision()<1||actual.schemaVersion()<1)throw new IllegalArgumentException();receipt=actual;status=actual.status();}}catch(Exception unavailable){status="UNAVAILABLE";}
        return new FeedbackTransactionOutcome(status,receipt!=null,item.state(),hash,receipt,false,false,false);
    }
}
