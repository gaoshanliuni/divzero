package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldActionIdentityMigrationTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    @Test void migratesPendingLegacyIdsAndGenerationOwnershipWithoutReplayingActions()throws Exception{
        UUID world=UUID.randomUUID(),id=UUID.randomUUID();Path database=root.resolve("actions.db");
        var actions=List.of(new WorldActionSpec("generate","create_world_package",0,0,0,"","",Map.of("prompt","fixture building")),new WorldActionSpec("finish","say",0,0,0,"","done"));
        var old=new WorldActionJournal.Batch(id,world,UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",actions,0,"EXECUTING","",Map.of("submitted","true"),List.of(),1,0);
        ObjectNode payload=json.valueToTree(old);payload.remove("operationIds");
        try(var ignored=new SqliteRuntimeRepository(database)){}
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);var insert=db.prepareStatement("INSERT INTO mineagent_runtime_records VALUES(?,?,?,?,?,?,?)")){
            insert.setString(1,world.toString());insert.setString(2,"world_action_batches_v1");insert.setString(3,id.toString());insert.setLong(4,1);insert.setString(5,payload.toString());insert.setLong(6,1);insert.setInt(7,0);insert.executeUpdate();
        }
        List<UUID> assigned;
        try(var journal=WorldActionJournal.open(database,world)){
            var restored=journal.get(id);assigned=restored.operationIds();assertEquals(2,assigned.size());assertEquals(3,assigned.getFirst().version());
            assertEquals("INTERRUPTED",restored.state());assertEquals(0,restored.cursor());assertEquals("SERVER_RESTARTED_NO_REPLAY",restored.error());
            assertEquals(id,journal.generationBatch(assigned.getFirst()).orElseThrow().batchId());assertThrows(IllegalStateException.class,()->journal.begin(id,Map.of()));
        }
        try(var journal=WorldActionJournal.open(database,world)){assertEquals(assigned,journal.get(id).operationIds());assertEquals("INTERRUPTED",journal.get(id).state());assertEquals(id,journal.generationBatch(assigned.getFirst()).orElseThrow().batchId());}
        try(var repo=new SqliteRuntimeRepository(database)){assertEquals(2,json.readTree(repo.get(world,"world_action_batches_v1",id.toString()).orElseThrow().payload()).path("operationIds").size());}
    }
    @Test void serializesExplicitActionIdsAndRejectsReceiptSubstitution()throws Exception{
        var action=new WorldActionSpec("speak","say",0,0,0,"","hello");var batch=new WorldActionJournal.Batch(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",List.of(action),0,"READY","",Map.of(),List.of(),1,0);
        assertEquals(4,batch.operationId().version());assertEquals(batch.operationId(),json.readValue(json.writeValueAsBytes(batch),WorldActionJournal.Batch.class).operationId());
        var forged=new WorldActionJournal.Receipt(UUID.randomUUID(),0,"say",true,"",Map.of(),Map.of());
        assertThrows(IllegalArgumentException.class,()->new WorldActionJournal.Batch(batch.batchId(),batch.worldId(),batch.taskId(),1,batch.agentId(),batch.owner(),batch.dimension(),batch.actions(),1,"COMPLETED","",Map.of(),List.of(forged),2,0,batch.operationIds()));
    }
}
