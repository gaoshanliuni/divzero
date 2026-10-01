package dev.mineagent.runtime.core.task;

import java.util.*;

/** Bounded step-size search retains the existing holdout and reference gates. Runs off the game thread. */
public final class LocalPolicyTrainer {
    public record Update(LocalActionPolicy model,boolean accepted,double before,double after,double referenceDrift,double scale,String reason){}
    public static Update fit(LocalActionPolicy serving,List<LocalActionPolicy.Sample> replay,List<LocalActionPolicy.Sample> reference){
        var train=new ArrayList<LocalActionPolicy.Sample>();var holdout=new ArrayList<LocalActionPolicy.Sample>();
        for(int i=0;i<replay.size();i++)(i%5==0?holdout:train).add(replay.get(i));
        if(train.size()>768)train=new ArrayList<>(train.subList(train.size()-768,train.size()));
        double before=serving.loss(holdout);var best=new Update(serving,false,before,before,serving.loss(reference),0,"INSUFFICIENT_REPLAY");
        if(replay.size()<128||holdout.size()<16)return best;
        var candidate=serving;best=new Update(serving,false,before,before,best.referenceDrift,0,"NO_IMPROVEMENT_WITHIN_REFERENCE_LIMIT");
        for(int epoch=0;epoch<3;epoch++){
            candidate=candidate.train(train,.002);
            for(double scale=1;scale>=1d/128;scale/=2){
                var trial=serving.interpolate(candidate,scale);double loss=trial.loss(holdout);
                if(loss<best.after-1e-8&&LocalActionPolicy.acceptsUpdate(serving,trial,holdout,reference))best=new Update(trial,true,before,loss,trial.loss(reference),scale,"VALIDATED_UPDATE");
            }
        }
        return best;
    }
    private LocalPolicyTrainer(){}
}
