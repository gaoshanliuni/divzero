package dev.mineagent.runtime.legacy189.bridge;

import dev.mineagent.runtime.legacy189.navigation.ComboRhythm;
import dev.mineagent.runtime.legacy189.navigation.CombatFootwork;
import org.junit.Test;
import static org.junit.Assert.*;

public class ComboRhythmTest {
    @Test public void confirmedHitReleasesAndReengagesSprint(){ComboRhythm rhythm=new ComboRhythm();rhythm.hit(100,3,.1);assertEquals(0,rhythm.forward(100,3,.1),0);assertEquals(0,rhythm.forward(101,3,.1),0);assertFalse(rhythm.sprint(101,1,false));assertEquals(1,rhythm.forward(102,3.1,.1),0);assertTrue(rhythm.sprint(102,1,false));assertEquals(100,rhythm.lastHit());}
    @Test public void closePressureProducesRealBackwardInput(){ComboRhythm rhythm=new ComboRhythm();rhythm.hit(100,2.3,-.1);assertTrue(rhythm.backTap());for(int tick=100;tick<103;tick++){assertTrue(rhythm.forward(tick,2.3,-.1)<0);assertFalse(rhythm.sprint(tick,1,false));}assertTrue(rhythm.sprint(103,rhythm.forward(103,2.8,0),false));}
    @Test public void onlyConfirmedHitsExtendTheChain(){ComboRhythm rhythm=new ComboRhythm();rhythm.hit(10,3,0);rhythm.hit(20,3,0);rhythm.hit(30,3,0);assertEquals(3,rhythm.maximum());assertEquals(0,rhythm.chain(55));rhythm.hit(56,3,0);assertEquals(1,rhythm.chain(56));rhythm.interrupted();assertEquals(0,rhythm.chain(57));assertEquals(3,rhythm.maximum());}
    @Test public void jumpTapUsesUpstreamGroundAndArcChecks(){CombatFootwork footwork=new CombatFootwork();assertFalse(footwork.jumpReady(30,true,false,true));assertFalse(footwork.jumpReady(30,true,true,false));assertFalse(footwork.jumpReady(30,false,true,true));assertTrue(footwork.jumpReady(30,true,true,true));footwork.jumped(30);assertFalse(footwork.jumpReady(50,true,true,true));assertTrue(footwork.jumpReady(58,true,true,true));ComboRhythm rhythm=new ComboRhythm();assertFalse(rhythm.sprint(100,1,true));}
}
