package dev.mineagent.runtime.core.task;

import java.util.*;

/** Statistics for one world visit. Time averages exclude draws and interrupted rounds. */
public record PvpMapProfile(long revision,int rounds,int wins,int losses,int draws,double killSeconds,double deathSeconds,
                            Map<String,String> human,Map<String,String> ai) {
    public static final List<String> SLOTS=List.of("head","chest","legs","feet","mainhand","offhand");
    public PvpMapProfile {
        if(revision<0||rounds<0||wins<0||losses<0||draws<0||wins+losses+draws!=rounds||!Double.isFinite(killSeconds)||!Double.isFinite(deathSeconds)||killSeconds<0||deathSeconds<0)throw new IllegalArgumentException("PVP_MAP_STATISTICS");
        human=Map.copyOf(human);ai=Map.copyOf(ai);
        if(!human.keySet().equals(Set.copyOf(SLOTS))||!ai.keySet().equals(Set.copyOf(SLOTS)))throw new IllegalArgumentException("PVP_MAP_SLOTS");
    }
    public static PvpMapProfile defaults(){var gear=Map.of("head","minecraft:iron_helmet","chest","minecraft:iron_chestplate","legs","minecraft:iron_leggings","feet","minecraft:iron_boots","mainhand","minecraft:diamond_sword","offhand","minecraft:air");return new PvpMapProfile(0,0,0,0,0,0,0,gear,gear);}
    public PvpMapProfile gear(long expected,String actor,String slot,String item){
        if(expected!=revision)throw new IllegalStateException("PVP_MAP_VERSION_CHANGED");
        if(!Set.of("human","ai").contains(actor)||!SLOTS.contains(slot)||item==null||!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("PVP_MAP_EQUIPMENT");
        var next=new LinkedHashMap<>(actor.equals("human")?human:ai);next.put(slot,item);
        return new PvpMapProfile(revision+1,rounds,wins,losses,draws,killSeconds,deathSeconds,actor.equals("human")?next:human,actor.equals("ai")?next:ai);
    }
    public PvpMapProfile finish(String outcome,double seconds){
        if(!Set.of("HUMAN_WON","AI_WON","TIME_LIMIT_DRAW","DOUBLE_KO").contains(outcome)||!Double.isFinite(seconds)||seconds<0||seconds>180)throw new IllegalArgumentException("PVP_MAP_RESULT");
        boolean win=outcome.equals("HUMAN_WON"),loss=outcome.equals("AI_WON");
        return new PvpMapProfile(revision+1,rounds+1,wins+(win?1:0),losses+(loss?1:0),draws+(!win&&!loss?1:0),killSeconds+(win?seconds:0),deathSeconds+(loss?seconds:0),human,ai);
    }
    public double winRate(){return rounds==0?0:100d*wins/rounds;}
    public Double averageKill(){return wins==0?null:killSeconds/wins;}
    public Double averageDeath(){return losses==0?null:deathSeconds/losses;}
}
