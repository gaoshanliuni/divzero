package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatPolicyTest {
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
}
