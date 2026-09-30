package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalActionPolicyTest {
    @Test void pretrainedModelRanksHazardHigherAndBoundsUnexpectedInputs(){
        var model=LocalActionPolicy.pretrained();double[] safe=new double[16];safe[0]=1;double[] danger=safe.clone();danger[6]=1;danger[10]=1;
        assertTrue(model.cost(danger)>model.cost(safe));danger[2]=Double.NaN;assertTrue(Double.isFinite(model.cost(danger)));
        assertEquals(model.cost(safe),LocalActionPolicy.parse(model.json()).cost(safe),1e-12);
    }
    @Test void learningChangesWeightsWithoutMutatingTheServingSnapshot(){
        var model=LocalActionPolicy.pretrained();var x=new double[16];x[0]=.8;x[6]=.3;double before=model.cost(x);
        var data=new ArrayList<LocalActionPolicy.Sample>();for(int i=0;i<256;i++)data.add(new LocalActionPolicy.Sample(x,0));var candidate=model.train(data,.005);
        assertEquals(before,model.cost(x),1e-12);assertTrue(candidate.loss(data)<model.loss(data));assertTrue(candidate.version()>model.version());
    }
}
