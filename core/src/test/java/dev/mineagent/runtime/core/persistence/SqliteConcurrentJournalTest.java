package dev.mineagent.runtime.core.persistence;

import dev.mineagent.runtime.core.conversation.ConversationToolJournal;
import dev.mineagent.runtime.core.recovery.ChangeJournalService;
import dev.mineagent.runtime.api.recovery.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteConcurrentJournalTest {
    @Test void deferredReadThenWriteReproducesTheWalUpgradeFailure(@TempDir Path directory)throws Exception {
        Path file=directory.resolve("original.db");UUID world=UUID.randomUUID();
        try(var initialized=new SqliteRuntimeRepository(file);
            var old=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath())){
            old.setAutoCommit(false);
            try(var q=old.createStatement();var rows=q.executeQuery("SELECT count(*) FROM mineagent_runtime_records")){assertTrue(rows.next());}
            ConversationToolJournal.save(file,world,UUID.randomUUID(),0,"{}");
            try(var q=old.createStatement()){
                var failure=assertThrows(java.sql.SQLException.class,()->q.executeUpdate("INSERT INTO mineagent_runtime_schema(version) VALUES(1)"));
                assertTrue(failure.getMessage().contains("SQLITE_BUSY"));
            }finally{old.rollback();old.setAutoCommit(true);}
        }
    }
    @Test void independentToolAndWorldJournalsDoNotLoseCasWrites(@TempDir Path directory)throws Exception {
        Path file=directory.resolve("runtime.db");UUID world=UUID.randomUUID(),owner=UUID.randomUUID();
        try(var changes=ChangeJournalService.open(file,world,Clock.systemUTC());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();
            // These are stress sample sizes, not application concurrency gates.
            for(int actor=0;actor<4;actor++)jobs.add(executor.submit(()->{start.await();for(int n=0;n<150;n++){
                UUID operation=UUID.randomUUID();ConversationToolJournal.save(file,world,operation,0,"{\"state\":\"DISPATCHING\"}");
                ConversationToolJournal.save(file,world,operation,1,"{\"status\":\"APPLIED\"}");
            }return null;}));
            jobs.add(executor.submit(()->{start.await();for(int n=0;n<300;n++)changes.record(owner,"PLACE_BLOCK",List.of(new BlockChange(
                    new BlockSnapshot("minecraft:overworld",n,64,0,"minecraft:air",""),
                    new BlockSnapshot("minecraft:overworld",n,64,0,"minecraft:stone",""))));return null;}));
            start.countDown();for(var job:jobs)job.get(60,TimeUnit.SECONDS);
            assertEquals(300,changes.all().size());
        }
        try(var reopened=ChangeJournalService.open(file,world,Clock.systemUTC());var repository=new SqliteRuntimeRepository(file)){
            assertEquals(300,reopened.all().size());
            var tools=repository.list(world,"conversation_agent_tools_v1");assertEquals(600,tools.size());
            assertTrue(tools.stream().allMatch(r->r.revision()==2));
        }
    }
}
