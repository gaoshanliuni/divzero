package dev.mineagent.runtime.core.objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
/** Persist this entire value before projecting absolute scores to the native scoreboard. */
public record InstanceScoreLedger(Map<String,Integer> scores,Map<String,Award> awards){
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Award(String holder,int delta){}
    public InstanceScoreLedger{scores=Map.copyOf(scores);awards=Map.copyOf(awards);if(scores.size()>32||awards.size()>512)throw new IllegalStateException("SCORE_LEDGER_CAPACITY");}
    public static InstanceScoreLedger parse(String value){if(value==null)return new InstanceScoreLedger(Map.of(),Map.of());try{if(value.length()>65536)throw new IllegalArgumentException();return JSON.readValue(value,InstanceScoreLedger.class);}catch(Exception e){throw new IllegalStateException("SCORE_LEDGER_INVALID",e);}}
    public InstanceScoreLedger award(String operation,String holder,int delta){
        if(operation==null||!operation.matches("[A-Za-z0-9_.:-]{1,80}")||holder==null||holder.isBlank()||holder.length()>64||holder.chars().anyMatch(Character::isISOControl)||delta==0||Math.abs((long)delta)>1000000)throw new IllegalArgumentException("SCORE_AWARD_ARGUMENTS");
        var award=new Award(holder,delta);var old=awards.get(operation);if(old!=null){if(!old.equals(award))throw new IllegalArgumentException("SCORE_AWARD_CONFLICT");return this;}
        var nextScores=new LinkedHashMap<>(scores);nextScores.put(holder,Math.addExact(scores.getOrDefault(holder,0),delta));var nextAwards=new LinkedHashMap<>(awards);nextAwards.put(operation,award);return new InstanceScoreLedger(nextScores,nextAwards);
    }
    public String encode(){try{String s=JSON.writeValueAsString(this);if(s.length()>65536)throw new IllegalStateException("SCORE_LEDGER_CAPACITY");return s;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
