package dev.mineagent.runtime.core.conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationToolJournalTest {
    @Test void largeReceiptsArePagedWithoutLossAndRemainOwnerAgentScoped(@TempDir Path root)throws Exception{
        Path file=root.resolve("runtime.db");UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),op=UUID.randomUUID();
        try(var ignored=new SqliteRuntimeRepository(file)){}
        var json=new ObjectMapper();String source="🙂正文".repeat(20000);var receipt=Map.of("status","UNKNOWN","output",source);
        ConversationToolJournal.save(file,world,op,0,json.writeValueAsString(Map.of("owner",owner,"agent",agent,"tool","large_receipt","state","UNKNOWN","receipt",receipt)));
        String listing=json.writeValueAsString(ConversationToolJournal.inspect(file,world,owner,agent,0));assertTrue(listing.length()<1000);assertFalse(listing.contains(source));
        var joined=new StringBuilder();int offset=0;do{var page=ConversationToolJournal.receipt(file,world,owner,agent,op,offset);assertEquals(false,page.get("replayAllowed"));joined.append(page.get("text"));offset=((Number)page.get("nextOffset")).intValue();}while(offset>=0);
        assertEquals(json.writeValueAsString(receipt),joined.toString());
        assertThrows(IllegalArgumentException.class,()->ConversationToolJournal.receipt(file,world,UUID.randomUUID(),agent,op,0));
        assertThrows(IllegalArgumentException.class,()->ConversationToolJournal.receipt(file,world,owner,UUID.randomUUID(),op,0));
        assertThrows(IllegalStateException.class,()->ConversationToolJournal.save(file,world,op,0,"{}"));
    }
}
