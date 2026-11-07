package dev.mineagent.runtime.core.feedback;
import dev.mineagent.runtime.core.shared.SharedStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FeedbackTransactionOutcomeTest {
    @TempDir Path dir;
    @Test void interruptedExecutionCanHaveAKnownReceiptWithoutCompletingOrReplayingTheItem()throws Exception{
        var fixture=new UiFeedbackDataPumpTest();fixture.root=dir;UUID id;
        try(var s=fixture.open()){id=fixture.submit(s).id();var p=fixture.pump(s);p.pump(4);p.pump(4);}
        try(var s=fixture.open()){var item=s.inspect(fixture.author,id);assertEquals("INTERRUPTED",item.state());
            var receipt=new SharedStateStore.Receipt("APPLIED",3,1,UUID.randomUUID(),"");var result=FeedbackTransactionOutcome.inspect(item,()->Optional.of(receipt));
            assertTrue(result.verified());assertEquals("APPLIED",result.status());assertFalse(result.replayAllowed());assertFalse(result.currentValuesVerified());assertEquals(item.dataPlan().sha256(),result.planSha256());
            assertEquals(item,s.inspect(fixture.author,id));assertNull(item.completion());assertEquals(1,fixture.planned);assertEquals(0,fixture.applied);
            var absent=FeedbackTransactionOutcome.inspect(item,Optional::empty);assertEquals("NOT_RECORDED",absent.status());assertFalse(absent.verified());
            var denied=FeedbackTransactionOutcome.inspect(item,()->{throw new SecurityException();});assertEquals("UNAVAILABLE",denied.status());assertFalse(denied.verified());
            var conflict=FeedbackTransactionOutcome.inspect(item,()->Optional.of(new SharedStateStore.Receipt("CONFLICT",3,1,null,"SHARED_CONDITION_CONFLICT")));assertTrue(conflict.verified());assertEquals("CONFLICT",conflict.status());
        }
    }
}
