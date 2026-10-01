package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatPolicyTest {
    @Test void creatorAssistanceDefaultsAndExplicitOptOutSurvivePersistence()throws Exception{
        var defaults=CombatPolicy.defaults(SkillSpec.Kind.FOLLOW,true,"");assertTrue(defaults.assistCreator());
        var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(defaults);legacy.remove("assistCreator");
        assertTrue(json.treeToValue(legacy,CombatPolicy.class).assistCreator());
        var disabled=CombatPolicy.parse(json.readTree("{\"assistCreator\":false}"),defaults);
        assertFalse(json.readValue(json.writeValueAsString(disabled),CombatPolicy.class).assistCreator());
        assertFalse(CombatPolicy.parse(json.readTree("{\"strategy\":\"MELEE_COMBO\"}"),disabled).withArea(null).assistCreator());
        assertThrows(IllegalArgumentException.class,()->CombatPolicy.parse(json.readTree("{\"assistCreator\":\"false\"}"),defaults));
    }
    @Test void creatorAssistanceRespectsModesAndNeverOverridesNoAttack()throws Exception{
        var policy=CombatPolicy.defaults(SkillSpec.Kind.FOLLOW,true,"");
        for(var kind:List.of(SkillSpec.Kind.FOLLOW,SkillSpec.Kind.WANDER,SkillSpec.Kind.COMBAT)){
            assertTrue(policy.assists(kind,true,false,false));assertTrue(policy.assists(kind,false,true,false));assertTrue(policy.assists(kind,false,false,true));assertFalse(policy.assists(kind,false,false,false));
            assertFalse(CombatPolicy.parse(json.readTree("{\"assistCreator\":false}"),policy).assists(kind,true,true,true));
            assertFalse(CombatPolicy.parse(json.readTree("{\"engagement\":\"NONE\"}"),policy).assists(kind,true,true,true));
        }
        for(var kind:List.of(SkillSpec.Kind.IDLE,SkillSpec.Kind.FARM,SkillSpec.Kind.FISH))assertFalse(policy.assists(kind,true,true,true));
        assertTrue(policy.assists(SkillSpec.Kind.PATROL,false,true,false));assertFalse(policy.assists(SkillSpec.Kind.PATROL,true,false,true));
    }
    @Test void recoveryDoesNotWaitForeverAfterFoodRunsOutOrHealingIsPrevented(){
        var recovery=new CombatRecoveryWindow();
        assertTrue(recovery.shouldRecover(100,5,20,false,100));
        assertFalse(recovery.shouldRecover(161,5,20,false,100));
        assertTrue(recovery.shouldRecover(162,5,20,true,100));
        assertFalse(recovery.shouldRecover(323,5,20,true,100));
        assertTrue(recovery.shouldRecover(324,5.5f,20,true,100));
        assertTrue(recovery.shouldRecover(500,4,20,false,499));
        assertFalse(recovery.shouldRecover(561,4,20,false,499));
        assertFalse(recovery.shouldRecover(562,8,20,true,499));
    }
    private final ObjectMapper json=new ObjectMapper();
    private SkillSpec farm()throws Exception{return SkillSpec.parse(json.readTree("""
        {"id":"farm","kind":"FARM","dimension":"minecraft:overworld","min":[0,64,0],"max":[4,65,4]}
        """),null);}
    @Test void changeStyleRetainsWorkAndProgressButInvalidatesOldRevision()throws Exception{
        var s=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,farm());s.cursor(18);s.add("planted",6);var id=s.id();var area=s.spec().area();
        s.combat(1,CombatPolicy.parse(json.readTree("{\"strategy\":\"HIT_AND_RUN\"}"),s.spec().combat()));
        assertEquals(id,s.id());assertEquals(area,s.spec().area());assertEquals(18,s.cursor());assertEquals(6,s.count("planted"));assertEquals(SkillSpec.Kind.FARM,s.spec().kind());assertEquals(CombatPolicy.Engagement.SELF_DEFENSE,s.spec().combat().engagement());
        assertThrows(IllegalStateException.class,()->s.combat(1,s.spec().combat()));
    }
    @Test void attackTargetIsIndependentFromFollowTarget()throws Exception{
        String enemy=UUID.randomUUID().toString();var n=json.readTree("{\"id\":\"follow\",\"kind\":\"FOLLOW\",\"dimension\":\"minecraft:overworld\",\"target\":\"$owner\",\"combat\":{\"engagement\":\"SPECIFIED\",\"target\":\""+enemy+"\"}}");var s=SkillSpec.parse(n,null);assertEquals("$owner",s.target());assertEquals(enemy,s.combat().target());
    }
    @Test void oldSnapshotWithoutNewFieldsRestoresPausedAndDoesNotReplay()throws Exception{
        var s=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,farm());var old=json.valueToTree(s.snapshot());((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("previous");var spec=(com.fasterxml.jackson.databind.node.ObjectNode)old.get("spec");spec.remove(List.of("combat","resumePrevious"));var restored=SkillSession.restore(json.treeToValue(old,SkillSession.Snapshot.class));assertEquals(SkillSession.State.PAUSED,restored.state());assertEquals(CombatPolicy.Strategy.AUTO,restored.spec().combat().strategy());
    }
    @Test void stoppingForbidsPolicyRevival()throws Exception{var s=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,farm());s.control(1,"stop");assertThrows(IllegalStateException.class,()->s.combat(2,s.spec().combat()));}
    @Test void protectionNeedsTargetAndClearanceNeedsRegion()throws Exception{
        assertThrows(IllegalArgumentException.class,()->CombatPolicy.parse(json.readTree("{\"engagement\":\"PROTECT\"}"),farm().combat()));
        var policy=CombatPolicy.parse(json.readTree("{\"engagement\":\"CLEAR_AREA\",\"min\":[8,64,8],\"max\":[9,65,9]}"),farm().combat());assertNotEquals(farm().area(),farm().withCombat(policy).combat().area());
    }
    @Test void temporaryWorkRelationshipSurvivesRestartWithoutRunning()throws Exception{
        var parent=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,farm());parent.control(1,"pause");parent.transition(SkillSession.State.PAUSED,"TEMPORARY_WORK");var child=new SkillSession(UUID.randomUUID(),parent.owner(),parent.agent(),parent.snapshot().world(),null,farm());child.previous(parent.id());
        assertEquals("TEMPORARY_WORK",SkillSession.restore(parent.snapshot()).reason());assertEquals(SkillSession.State.PAUSED,SkillSession.restore(parent.snapshot()).state());assertEquals(parent.id(),SkillSession.restore(child.snapshot()).previous());assertEquals(SkillSession.State.PAUSED,SkillSession.restore(child.snapshot()).state());
    }
}
