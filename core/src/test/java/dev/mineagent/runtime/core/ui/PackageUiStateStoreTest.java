package dev.mineagent.runtime.core.ui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PackageUiStateStoreTest {
    @TempDir Path dir;
    final UUID world=UUID.randomUUID(),viewer=UUID.randomUUID(),pkg=UUID.randomUUID();
    PackageUiStateStore.Scope scope(){return new PackageUiStateStore.Scope("local-integrated",world,viewer,pkg,"ui/index.html","");}
    @Test void preservesLegacyScopeDataAndItsRevisionThroughTheDurableIdentityMapping()throws Exception{
        String legacy=dev.mineagent.runtime.core.content.LegacyNamespaceIds.packageUiStateForMigration("local-integrated",viewer,pkg,"ui/index.html","");
        try(var repo=new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(dir.resolve("client.db"))){
            assertTrue(repo.compareAndSet(world,"client_package_ui_state",legacy,0,"{\"schema\":1,\"packageRevision\":2,\"entries\":{\"notes\":\"[1]\"}}",1).accepted());
        }
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            assertEquals("[1]",store.get(scope(),"notes").valueJson());assertEquals(1,store.get(scope(),"notes").revision());
            store.put(scope(),"notes",1,3,"[2]",()->true);
        }
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){assertEquals("[2]",store.get(scope(),"notes").valueJson());assertEquals(2,store.get(scope(),"notes").revision());}
        try(var repo=new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(dir.resolve("client.db"))){assertEquals(1,repo.list(world,"client_package_ui_state").size());assertEquals(2,repo.get(world,"client_package_ui_state",legacy).orElseThrow().revision());}
    }
    @Test void committedJsonSurvivesReopenAndCodeRevisionWithoutBecomingWorldState()throws Exception{
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            var empty=store.get(scope(),"notes");assertEquals(0,empty.revision());assertFalse(empty.exists());
            var saved=store.put(scope(),"notes",0,2,"[{\"who\":\"巡逻员\"}]",()->true);assertEquals(1,saved.revision());
        }
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            var loaded=store.get(scope(),"notes");assertTrue(loaded.valueJson().contains("巡逻员"));
            assertEquals(2,store.put(scope(),"notes",loaded.revision(),3,"[]",()->true).revision());
            assertThrows(IllegalStateException.class,()->store.put(scope(),"notes",2,2,"[]",()->true));
        }
    }
    @Test void casAndInvalidationNeverOverwriteTheCurrentDocument()throws Exception{
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            store.put(scope(),"notes",0,1,"{\"count\":1}",()->true);
            assertThrows(IllegalStateException.class,()->store.put(scope(),"notes",0,1,"{\"count\":2}",()->true));
            assertThrows(IllegalStateException.class,()->store.put(scope(),"notes",1,1,"{\"count\":3}",()->false));
            assertEquals("{\"count\":1}",store.get(scope(),"notes").valueJson());assertEquals(1,store.get(scope(),"notes").revision());
        }
    }
    @Test void serverWorldViewerPackageEntryAndTargetAreAllIsolated()throws Exception{
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            store.put(scope(),"notes",0,1,"\"private draft\"",()->true);
            for(var other:new PackageUiStateStore.Scope[]{
                new PackageUiStateStore.Scope("other",world,viewer,pkg,"ui/index.html",""),
                new PackageUiStateStore.Scope("local-integrated",UUID.randomUUID(),viewer,pkg,"ui/index.html",""),
                new PackageUiStateStore.Scope("local-integrated",world,UUID.randomUUID(),pkg,"ui/index.html",""),
                new PackageUiStateStore.Scope("local-integrated",world,viewer,UUID.randomUUID(),"ui/index.html",""),
                new PackageUiStateStore.Scope("local-integrated",world,viewer,pkg,"ui/another.html",""),
                new PackageUiStateStore.Scope("local-integrated",world,viewer,pkg,"ui/index.html","target")})assertFalse(store.get(other,"notes").exists());
        }
    }
    @Test void removalPreservesRevisionAndInvalidOrExcessiveJsonIsRejected()throws Exception{
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            store.put(scope(),"notes",0,1,"[1]",()->true);var removed=store.remove(scope(),"notes",1,1,()->true);
            assertFalse(removed.exists());assertEquals(2,removed.revision());
            assertThrows(IllegalStateException.class,()->store.put(scope(),"notes",0,1,"[2]",()->true));
            assertThrows(IllegalArgumentException.class,()->store.put(scope(),"../foreign",2,1,"null",()->true));
            assertThrows(IllegalArgumentException.class,()->store.put(scope(),"notes",2,1,"not json",()->true));
            assertThrows(IllegalArgumentException.class,()->store.put(scope(),"notes",2,1,"\""+"x".repeat(32769)+"\"",()->true));
        }
    }
    @Test void keyAndAggregateBudgetsDoNotPartiallyPersist()throws Exception{
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            long revision=0;for(int i=0;i<64;i++)revision=store.put(scope(),"k"+i,revision,1,"null",()->true).revision();
            long expected=revision;assertThrows(IllegalArgumentException.class,()->store.put(scope(),"overflow",expected,1,"null",()->true));assertEquals(expected,store.get(scope(),"k0").revision());
        }
    }
    @Test void separateStoreConnectionsObserveTheSameRevisionFence()throws Exception{
        try(var a=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC());var b=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            var baseline=b.get(scope(),"notes");a.put(scope(),"notes",baseline.revision(),1,"[1]",()->true);
            assertThrows(IllegalStateException.class,()->b.put(scope(),"notes",baseline.revision(),1,"[2]",()->true));assertEquals("[1]",b.get(scope(),"notes").valueJson());
        }
    }
    @Test void aggregateValueBudgetRejectsBeforeAnyCommit()throws Exception{
        try(var store=new PackageUiStateStore(dir.resolve("client.db"),Clock.systemUTC())){
            String value="\""+"x".repeat(30000)+"\"";long revision=0;
            for(int i=0;i<8;i++)revision=store.put(scope(),"v"+i,revision,1,value,()->true).revision();
            long expected=revision;assertThrows(IllegalArgumentException.class,()->store.put(scope(),"overflow",expected,1,value,()->true));
            assertEquals(expected,store.get(scope(),"v0").revision());assertFalse(store.get(scope(),"overflow").exists());
        }
    }
}
