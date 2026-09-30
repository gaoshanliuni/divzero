package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Small bounded value network. It ranks admissible Java actions; it never executes an action itself. */
public final class LocalActionPolicy {
    public static final int INPUTS=16,HIDDEN=24;
    public static final String SCHEMA="divzero-admissible-action-value/2";
    public record Weights(String schema,long version,double[][] hidden,double[] bias,double[] output,double outputBias,String provenance){}
    public record Sample(double[] features,double cost){public Sample{features=features.clone();if(features.length!=INPUTS||!Double.isFinite(cost)||cost<0||cost>1)throw new IllegalArgumentException("POLICY_SAMPLE");}@Override public double[] features(){return features.clone();}}
    private final Weights weights;
    public LocalActionPolicy(Weights value){
        if(value.provenance==null||value.provenance.length()>256||!SCHEMA.equals(value.schema)||value.hidden.length!=HIDDEN||value.bias.length!=HIDDEN||value.output.length!=HIDDEN||value.version<1)throw new IllegalArgumentException("POLICY_SCHEMA");
        double[][] hidden=new double[HIDDEN][];for(int i=0;i<HIDDEN;i++){if(value.hidden[i].length!=INPUTS)throw new IllegalArgumentException("POLICY_SHAPE");hidden[i]=value.hidden[i].clone();for(double v:hidden[i])finite(v);finite(value.bias[i]);finite(value.output[i]);}finite(value.outputBias);
        weights=new Weights(value.schema,value.version,hidden,value.bias.clone(),value.output.clone(),value.outputBias,value.provenance);
    }
    private static void finite(double v){if(!Double.isFinite(v)||Math.abs(v)>64)throw new IllegalArgumentException("POLICY_WEIGHT");}
    private static double input(double[] x,int i){return Double.isFinite(x[i])?Math.clamp(x[i],-2,2):0;}
    private double[] hidden(double[] x){if(x.length!=INPUTS)throw new IllegalArgumentException("POLICY_FEATURES");double[] h=new double[HIDDEN];for(int i=0;i<HIDDEN;i++){double value=weights.bias[i];for(int j=0;j<INPUTS;j++)value+=weights.hidden[i][j]*input(x,j);h[i]=Math.tanh(value);}return h;}
    public double cost(double[] features){var h=hidden(features);double out=weights.outputBias;for(int i=0;i<HIDDEN;i++)out+=weights.output[i]*h[i];return 1/(1+Math.exp(-Math.clamp(out,-30,30)));}
    public long version(){return weights.version;}
    public String source(){return weights.provenance;}
    public String json(){try{return new ObjectMapper().writeValueAsString(weights);}catch(Exception error){throw new IllegalStateException(error);}}
    public static LocalActionPolicy parse(String json){try{return new LocalActionPolicy(new ObjectMapper().readValue(json,Weights.class));}catch(Exception invalid){throw new IllegalArgumentException("POLICY_CHECKPOINT_INVALID",invalid);}}
    public static LocalActionPolicy pretrained(){try(var in=LocalActionPolicy.class.getResourceAsStream("/dev/mineagent/runtime/policy/pretrained.json")){if(in==null)throw new IllegalStateException("POLICY_PRETRAINED_MISSING");return parse(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    public double loss(List<Sample> samples){if(samples.isEmpty())return 0;double loss=0;for(var sample:samples){double delta=cost(sample.features)-sample.cost;loss+=delta*delta;}return loss/samples.size();}
    public LocalActionPolicy train(List<Sample> samples,double rate){
        if(samples.isEmpty()||samples.size()>1024||rate<=0||rate>.05)throw new IllegalArgumentException("POLICY_TRAINING_INPUT");
        double[][] wh=new double[HIDDEN][];for(int i=0;i<HIDDEN;i++)wh[i]=weights.hidden[i].clone();double[] bh=weights.bias.clone(),wo=weights.output.clone();double bo=weights.outputBias;
        for(var sample:samples){var h=new double[HIDDEN];for(int i=0;i<HIDDEN;i++){double value=bh[i];for(int j=0;j<INPUTS;j++)value+=wh[i][j]*input(sample.features,j);h[i]=Math.tanh(value);}double out=bo;for(int i=0;i<HIDDEN;i++)out+=wo[i]*h[i];double predicted=1/(1+Math.exp(-Math.clamp(out,-30,30))),gradient=Math.clamp(predicted-sample.cost,-1,1);
            for(int i=0;i<HIDDEN;i++){double hiddenGradient=gradient*wo[i]*(1-h[i]*h[i]);for(int j=0;j<INPUTS;j++)wh[i][j]=Math.clamp(wh[i][j]-rate*hiddenGradient*input(sample.features,j),-8,8);bh[i]=Math.clamp(bh[i]-rate*hiddenGradient,-8,8);wo[i]=Math.clamp(wo[i]-rate*gradient*h[i],-8,8);}bo=Math.clamp(bo-rate*gradient,-8,8);
        }return new LocalActionPolicy(new Weights(SCHEMA,Math.addExact(weights.version,1),wh,bh,wo,bo,"LOCAL_VERIFIED_OUTCOMES"));
    }
}
