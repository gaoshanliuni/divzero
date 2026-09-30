package dev.mineagent.runtime.core.conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionRecordsTest {
    @TempDir Path directory;
    @Test void fullRecordKeepsUncertainOperationAndCannotCrossOwnerOrAgent()throws Exception{
        var database=directory.resolve("records.db");var scope=new ExecutionRecords.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var id=UUID.randomUUID();
        ExecutionRecords.write(database,scope,id,UUID.randomUUID(),UUID.randomUUID(),3,"apply_building",new ObjectMapper().readTree("{\"id\":\"house\",\"revision\":7}"),Map.of("status","UNKNOWN","operation","native-op","detail","完整结果".repeat(500)),10);
        var first=ExecutionRecords.read(database,scope,id,0,8192);String text=first.get("text").toString();assertTrue(text.contains("UNKNOWN"));assertTrue(text.contains("native-op"));assertTrue(text.contains("\"revision\":7"));assertEquals(false,first.get("replayAllowed"));
        assertThrows(SecurityException.class,()->ExecutionRecords.read(database,new ExecutionRecords.Scope(scope.world(),UUID.randomUUID(),scope.agent()),id,0,4096));
        assertThrows(SecurityException.class,()->ExecutionRecords.read(database,new ExecutionRecords.Scope(scope.world(),scope.owner(),UUID.randomUUID()),id,0,4096));
    }
}
