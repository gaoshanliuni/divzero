package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class WorkerThinkingTest {
 @TempDir java.nio.file.Path dir;
    @Test void conversationThinkingUsesTypedDeltasWithOneSequenceAndNoAnswerContamination()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{e.getRequestBody().readAllBytes();byte[] bytes=("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking-only\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"answer-only\"}}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(dir.resolve("runtime.db"));var handler=new WorkerRequestHandler()){assertEquals("storage.configured",handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",dir.resolve("content").toString()))).type());handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind","openai-compatible","baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","model","test","apiKey","fixture")));
            var deltas=new java.util.ArrayList<WorkerEnvelope>();var result=handler.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",Map.of("capability","SEMANTIC","prompt","question","conversationTools",true)),deltas::add);
            assertEquals("model.stream.result",result.type(),result.toString());assertEquals(2,deltas.size());assertEquals("thinking",deltas.getFirst().payload().get("channel"));assertEquals(0,deltas.getFirst().payload().get("sequence"));assertEquals(1,deltas.getLast().payload().get("sequence"));assertEquals("answer-only",result.payload().get("text"));
        }finally{server.stop(0);}
    }
}
