package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PvpMapProfileTest {
    @Test void independentGearAndPersistentStatistics(){
        var p=PvpMapProfile.defaults().gear(0,"ai","chest","minecraft:diamond_chestplate");
        assertEquals("minecraft:iron_chestplate",p.human().get("chest"));
        var changed=p;assertThrows(IllegalStateException.class,()->changed.gear(0,"human","mainhand","minecraft:air"));
        p=p.finish("HUMAN_WON",20).finish("AI_WON",30).finish("HUMAN_WON",40).finish("TIME_LIMIT_DRAW",180);
        assertEquals(4,p.rounds());assertEquals(50,p.winRate());assertEquals(30d,p.averageKill().doubleValue());assertEquals(30d,p.averageDeath().doubleValue());
        assertNull(PvpMapProfile.defaults().averageKill());
    }
    @Test void woolIsIndependentAndResetsOnNewVisit(){var p=PvpMapProfile.defaults().wool(0,"human",true);assertTrue(p.humanWool());assertFalse(p.aiWool());p=p.finish("HUMAN_WON",20);assertTrue(p.humanWool());assertFalse(PvpMapProfile.defaults().humanWool());}
    @Test void mapRoundsAreUnlimitedAndResumeFromSavedTotal(){
        var s=new HumanDuelSeries(0,12);
        for(int i=0;i<50;i++){assertTrue(s.ready(0));s.starting();s.started(0);assertTrue(s.finish());assertEquals(HumanDuelSeries.Phase.BETWEEN,s.phase());}
        assertEquals(62,s.completed());assertEquals(63,s.round());
    }
}
