package dev.mineagent.runtime.core.compile;
import dev.mineagent.runtime.core.task.WorldActionSpec;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeKnowledgeToolRequestTest {
    @Test void parsesStrictCanonicalKnowledgeLifecycle(){
        UUID observation=UUID.randomUUID(),knowledge=UUID.randomUUID();var remember=NativeKnowledgeToolRequest.parse("remember_native_api","{\"observation_id\":\""+observation+"\",\"label\":\"Server tick\"}");assertEquals(observation,remember.observation());assertEquals("{\"observation_id\":\""+observation+"\",\"label\":\"Server tick\"}",remember.canonical());
        assertEquals(knowledge,NativeKnowledgeToolRequest.parse("inspect_native_knowledge","{\"knowledge_id\":\""+knowledge+"\"}").knowledge());assertEquals(16,NativeKnowledgeToolRequest.parse("search_native_knowledge","{\"query\":\"server\",\"offset\":16}").offset());
        for(String tool:List.of("revalidate_native_knowledge","forget_native_knowledge"))assertEquals(knowledge,NativeKnowledgeToolRequest.parse(tool,"{\"knowledge_id\":\""+knowledge+"\"}").knowledge());
        var action=assertDoesNotThrow(()->WorldActionSpec.parse(List.of(Map.of("id","remember","name","remember_native_api","arguments",remember.canonical()))).getFirst());assertFalse(action.hasPosition());assertEquals(remember.canonical(),action.options().get("request"));
        assertThrows(IllegalArgumentException.class,()->WorldActionSpec.parse(List.of(Map.of("id","remember","name","remember_native_api","arguments",remember.canonical()),Map.of("id","say","name","say","arguments","{\"message\":\"done\"}"))));
        assertThrows(IllegalArgumentException.class,()->NativeKnowledgeToolRequest.parse("remember_native_api","{\"observation_id\":\""+observation+"\",\"label\":\"x\",\"owner\":\"bad\"}"));
        var goal=dev.mineagent.runtime.core.task.WorldGoalCheck.parse("{\"checks\":[{\"kind\":\"native_knowledge\",\"knowledge_id\":\""+knowledge+"\",\"state\":\"CURRENT\",\"semantic_hash\":\""+"a".repeat(64)+"\"}]}").getFirst();assertEquals("native_knowledge",goal.kind());assertEquals("CURRENT",goal.details().get("state"));
    }
}
