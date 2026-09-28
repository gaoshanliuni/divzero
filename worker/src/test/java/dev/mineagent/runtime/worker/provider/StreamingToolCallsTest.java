package dev.mineagent.runtime.worker.provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StreamingToolCallsTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void constructionSourceCanSpanManyWidgetsButOtherToolsKeepTheirArgumentBound()throws Exception {
        String arguments=JSON.writeValueAsString(java.util.Map.of("source","x".repeat(32000),"revision",0));
        for(String name:java.util.List.of("plan_building","run_game_command")){
            var stream=new StreamingToolCalls();stream.accept(JSON.valueToTree(java.util.Map.of("tool_calls",java.util.List.of(java.util.Map.of("index",0,"id","call","function",java.util.Map.of("name",name,"arguments",arguments))))));
            if(name.equals("plan_building"))assertEquals(arguments,stream.finish().getFirst().argumentsJson());else assertThrows(IllegalArgumentException.class,stream::finish);
        }
    }
    @Test void builderBatchUsesTheSameBoundAsServerAdmission()throws Exception{
        var stream=new StreamingToolCalls();int maximum=dev.mineagent.runtime.core.conversation.ConversationTools.MAX_CALLS_PER_ROUND;
        for(int i=0;i<maximum;i++)stream.accept(JSON.readTree("{\"tool_calls\":[{\"index\":"+i+",\"id\":\"call_"+i+"\",\"function\":{\"name\":\"run_game_command\",\"arguments\":\"{}\"}}]}"));
        assertEquals(maximum,stream.finish().size());assertThrows(IllegalArgumentException.class,()->stream.accept(JSON.readTree("{\"tool_calls\":[{\"index\":"+maximum+"}]}")));
    }
    @Test void reassemblesFragmentedNamesAndJsonWithoutRunningPartialCalls()throws Exception{var a=new StreamingToolCalls();a.accept(JSON.readTree("{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"inspect_\",\"arguments\":\"{\\\"sec\"}}]}"));a.accept(JSON.readTree("{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"player\",\"arguments\":\"tion\\\":\\\"summary\\\"}\"}}]}"));var calls=a.finish();assertEquals(1,calls.size());assertEquals("inspect_player",calls.getFirst().name());assertEquals("summary",JSON.readTree(calls.getFirst().argumentsJson()).path("section").asText());}
    @Test void malformedAndExcessiveCallsFailClosed()throws Exception{var a=new StreamingToolCalls();assertThrows(Exception.class,()->a.accept(JSON.readTree("{\"tool_calls\":{}}")));assertThrows(Exception.class,()->a.accept(JSON.readTree("{\"tool_calls\":[{\"index\":16}]}")));assertThrows(Exception.class,()->a.accept(JSON.readTree("{\"tool_calls\":[{\"index\":4294967296}]}")));var b=new StreamingToolCalls();b.accept(JSON.readTree("{\"tool_calls\":[{\"index\":0}]}"));assertThrows(Exception.class,b::finish);}
}
