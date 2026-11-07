package dev.mineagent.runtime.core.persistence;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class AssignedOperationIdsTest {
    @TempDir Path directory;
    @Test void keepsRecordedOldIdsAndScopesNewMappings()throws Exception{UUID world=UUID.randomUUID(),legacy=UUID.randomUUID();Path file=directory.resolve("ids.db");try(var db=new SqliteRuntimeRepository(file)){assertEquals(legacy,AssignedOperationIds.resolve(db,world,"publication_ids_v2","draft:1",List.of(legacy)));assertNotEquals(legacy,AssignedOperationIds.resolve(db,UUID.randomUUID(),"publication_ids_v2","draft:1",List.of()));}try(var db=new SqliteRuntimeRepository(file)){assertEquals(legacy,AssignedOperationIds.resolve(db,world,"publication_ids_v2","draft:1",List.of()));}}
    @Test void competingConnectionsReserveOneOperation()throws Exception{UUID world=UUID.randomUUID();Path file=directory.resolve("race.db");try(var initial=new SqliteRuntimeRepository(file)){}try(var pool=Executors.newFixedThreadPool(4)){var gate=new CountDownLatch(1);var futures=new ArrayList<Future<UUID>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{try(var db=new SqliteRuntimeRepository(file)){gate.await();return AssignedOperationIds.resolve(db,world,"publication_ids_v2","draft:1",List.of());}}));gate.countDown();var ids=new HashSet<UUID>();for(var future:futures)ids.add(future.get(10,TimeUnit.SECONDS));assertEquals(1,ids.size());}}
}
