package dev.mineagent.runtime.core.building;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BuildingDesignStoreTest {
    @TempDir Path directory;
    private static final String SOURCE="""
        {"id":"home","name":"House","dimension":"minecraft:overworld","origin":[0,80,0],"components":[{"id":"floor","parts":[{"kind":"box","min":[0,0,0],"max":[4,0,4],"material":"minecraft:stone"}]}]}
        """;
    @Test void designSurvivesRestartAndStaleWritersCannotOverwrite()throws Exception{
        Path db=directory.resolve("designs.db");var scope=new BuildingDesignStore.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        try(var a=new BuildingDesignStore(db,Clock.systemUTC());var b=new BuildingDesignStore(db,Clock.systemUTC())){
            var first=a.save(scope,0,SOURCE);assertEquals(Set.of("floor"),first.changedComponents());assertEquals(1,b.get(scope,"home").orElseThrow().revision());
            a.save(scope,1,SOURCE.replace("minecraft:stone","minecraft:gold_block"));
            assertThrows(IllegalStateException.class,()->b.save(scope,1,SOURCE));
            var otherOwner=new BuildingDesignStore.Scope(scope.world(),UUID.randomUUID(),scope.agent());assertTrue(b.get(otherOwner,"home").isEmpty());
            var otherWorld=new BuildingDesignStore.Scope(UUID.randomUUID(),scope.owner(),scope.agent());assertTrue(b.get(otherWorld,"home").isEmpty());
        }
        try(var reopened=new BuildingDesignStore(db,Clock.systemUTC())){var saved=reopened.get(scope,"home").orElseThrow();assertEquals(2,saved.revision());assertTrue(saved.design().geometry("floor").contains("gold_block"));}
    }
}
