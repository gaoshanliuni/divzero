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
    @Test void liveHoldoutImprovementAloneCannotReplaceTheReferenceBehavior(){
        var before=LocalActionPolicy.pretrained();var anchors=LocalActionPolicy.referenceSamples(before);var live=new ArrayList<LocalActionPolicy.Sample>();
        for(var sample:anchors)live.add(new LocalActionPolicy.Sample(sample.features(),0));
        var candidate=before;for(int i=0;i<25;i++)candidate=candidate.train(live,.02);
        assertTrue(candidate.loss(live)<before.loss(live));assertFalse(LocalActionPolicy.acceptsUpdate(before,candidate,live,anchors));
        assertEquals(0,before.loss(anchors),1e-15);assertFalse(LocalActionPolicy.acceptsUpdate(before,before,live,anchors));
    }
}
