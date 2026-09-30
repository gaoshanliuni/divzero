package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;import com.sun.net.httpserver.HttpServer;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class WorkerAgentModelTest {
 @TempDir java.nio.file.Path root;
 @Test void requestLocalModelsNeverMutateDefaultsOrCrossAgentsAndRejectChangedEndpoints()throws Exception{
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var seen=new ArrayList<String>();var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",e->{var n=json.readTree(e.getRequestBody());String model=n.path("model").asText();seen.add(model);String body=n.path("stream").asBoolean()?"data: {\"model\":\""+model+"\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n":"{\"model\":\""+model+"\",\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});http.start();
  String url="http://127.0.0.1:"+http.getAddress().getPort()+"/v1/",w=UUID.randomUUID().toString(),a=UUID.randomUUID().toString();
  try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(root.resolve("runtime.db"));var handler=new WorkerRequestHandler()){assertEquals("storage.configured",handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",root.resolve("content").toString()))).type());handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind","openai-compatible","baseUrl",url,"model","global","apiKey","fixture")));
   for(String capability:List.of("SEMANTIC","PLANNING","CODING")){var p=new LinkedHashMap<String,Object>(Map.of("capability",capability,"prompt","question","worldId",w,"agentId",a));p.put("agentModel",Map.of("world",w,"agent",a,"model","special","baseUrl",url,"revision",1));var result=handler.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",p),d->{});assertEquals("special",result.payload().get("requestedModel"),result.toString());}
   var normal=handler.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",Map.of("capability","SEMANTIC","prompt","other Agent")),d->{});assertEquals("global",normal.payload().get("requestedModel"));
   var p=new LinkedHashMap<String,Object>(Map.of("capability","SEMANTIC","prompt","stale","worldId",w,"agentId",a));p.put("agentModel",Map.of("world",w,"agent",a,"model","wrong","baseUrl",url+"other/","revision",1));assertEquals("error",handler.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",p),d->{}).type());assertEquals(List.of("special","special","special","global"),seen);
   var settings=new LinkedHashMap<String,String>();settings.put("provider.openai.baseUrl",url);for(String role:dev.mineagent.runtime.core.config.ExplicitModelRoles.ROLES)settings.put("provider.openai.role."+role,"fixture-"+role);
   assertTrue(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.revision(),settings),true).accepted());
   for(String role:dev.mineagent.runtime.core.config.ExplicitModelRoles.ROLES){
    var source=dev.mineagent.runtime.core.config.AgentModelSettings.context(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",Map.of("capability","SEMANTIC","prompt","explicit role")),UUID.fromString(w),UUID.fromString(a));
    var bound=dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,dev.mineagent.runtime.core.config.ExplicitModelRoles.mark(source,role));
    var reply=handler.handleStreaming(bound,d->{});assertEquals("fixture-"+role,reply.payload().get("requestedModel"));assertEquals("fixture-"+role,seen.getLast());
   }
   var inherited=handler.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",Map.of("capability","SEMANTIC","prompt","default remains")),d->{});assertEquals("global",inherited.payload().get("requestedModel"));

  }finally{http.stop(0);}
 }
}
