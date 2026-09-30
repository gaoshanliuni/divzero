package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KnownPortalRoutesTest {
    @TempDir Path root;
    private static KnownPortalRoutes.Location at(String dimension,int x){return new KnownPortalRoutes.Location("minecraft:"+dimension,new SkillSpec.Point(x,64,0));}
    @Test void scopedActualDirectedTransitionsDoNotInventReturnPaths()throws Exception{
        var scope=new KnownPortalRoutes.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        try(var store=new KnownPortalRoutes(root.resolve("routes.db"))){
            var first=store.observe(scope,at("overworld",20),at("the_nether",91),"minecraft:nether_portal",scope.agent(),100);
            var plan=KnownPortalRoutes.plan(at("overworld",0),at("the_nether",100),store.list(scope));assertEquals(3,plan.legs().size());assertFalse(plan.navigationVerified());assertEquals(91,plan.legs().get(1).to().position().x());
            assertThrows(IllegalStateException.class,()->KnownPortalRoutes.plan(at("the_nether",100),at("overworld",0),store.list(scope)));
            assertTrue(store.list(new KnownPortalRoutes.Scope(scope.world(),UUID.randomUUID(),scope.agent())).isEmpty());
            var changed=store.observe(scope,at("overworld",20),at("the_nether",110),"minecraft:nether_portal",scope.agent(),200);assertEquals(first.id(),changed.id());assertEquals(2,changed.revision());assertEquals(1,store.list(scope).size());
        }
    }
    @Test void choosesCheapestKnownChainAndLeavesTerrainValidationToExecution(){
        UUID actor=UUID.randomUUID();var links=List.of(new KnownPortalRoutes.Link("far",1,at("overworld",500),at("the_end",5),"minecraft:end_portal",actor,1),new KnownPortalRoutes.Link("a",1,at("overworld",5),at("the_nether",8),"minecraft:nether_portal",actor,1),new KnownPortalRoutes.Link("b",1,at("the_nether",10),at("the_end",7),"test:portal",actor,1));
        var plan=KnownPortalRoutes.plan(at("overworld",0),at("the_end",9),links);assertEquals(5,plan.legs().size());assertEquals(List.of("a","b"),plan.legs().stream().filter(l->l.action().equals("NATIVE_PORTAL")).map(KnownPortalRoutes.Leg::portalId).toList());assertFalse(plan.navigationVerified());
    }
}
