package dev.mineagent.runtime.core.task;

import java.util.*;

/** Mirrored task conditions are independent from model identity and training/evaluation mode. */
public final class SelfPlaySchedule {
    public enum Scenario { MELEE, MIXED_WEAPONS, OUTNUMBERED, AIR_GROUND, CONFINED }
    public enum Role { MELEE, RANGED, AIRBORNE_START, CONFINED_START }
    public record Match(int wave,int lane,Scenario scenario,Role left,Role right,int leftCount,int rightCount,int leftModel,int rightModel){}
    public static List<Match> wave(int wave,int models){
        if(wave<0||models<2)throw new IllegalArgumentException("SELF_PLAY_SCHEDULE");var matches=new ArrayList<Match>();
        boolean mirror=wave%2!=0;int first=(wave/2)%models,second=(first+1+(wave/2)/(models))%models;if(second==first)second=(first+1)%models;
        for(var scene:Scenario.values()){
            Role left=scene==Scenario.AIR_GROUND?Role.AIRBORNE_START:scene==Scenario.CONFINED?Role.CONFINED_START:Role.MELEE;
            Role right=scene==Scenario.MIXED_WEAPONS?Role.RANGED:Role.MELEE;
            int leftCount=1,rightCount=scene==Scenario.OUTNUMBERED?(wave/2%2==0?3:5):1;
            matches.add(new Match(wave,scene.ordinal(),scene,mirror?right:left,mirror?left:right,mirror?rightCount:leftCount,mirror?leftCount:rightCount,first,second));
        }
        return List.copyOf(matches);
    }
    private SelfPlaySchedule(){}
}
