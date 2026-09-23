package dev.mineagent.runtime.worker.provider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class ProviderModelCatalogTest {
 @Test void resolvesRootVersionAndCompatibleVendorPaths(){
  assertEquals("https://api.deepseek.com/v1/models",ProviderModelCatalog.endpoint("https://api.deepseek.com").toString());
  for(String base:new String[]{"https://example.test/v1","https://example.test/v1/"})assertEquals("https://example.test/v1/models",ProviderModelCatalog.endpoint(base).toString());
  assertEquals("https://open.bigmodel.cn/api/paas/v4/models",ProviderModelCatalog.endpoint("https://open.bigmodel.cn/api/paas/v4/").toString());
  for(String base:new String[]{"file:///etc","https://user:password@example.test/v1/","https://example.test/v1/?key=x","https://example.test/#key"})assertThrows(IllegalArgumentException.class,()->ProviderModelCatalog.endpoint(base));
 }
 @Test void metadataGetUsesBearerAndNeverRedirectsOrReturnsRawErrors()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var response=new AtomicReference<>("{\"data\":[{\"id\":\"z\"},{\"id\":\"a\"},{\"id\":\"z\"}]}");var status=new AtomicInteger(200);var calls=new AtomicInteger();var forwarded=new AtomicInteger();
  server.createContext("/v1/models",exchange->{calls.incrementAndGet();assertEquals("GET",exchange.getRequestMethod());assertEquals("Bearer test-private-key",exchange.getRequestHeaders().getFirst("Authorization"));if(status.get()==302)exchange.getResponseHeaders().add("Location","/destination");byte[] body=response.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
  server.createContext("/destination",exchange->{forwarded.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();try{
   var ok=ProviderModelCatalog.fetch(base,"test-private-key");assertEquals(java.util.List.of("a","z"),ok.models());assertEquals("",ok.error());assertEquals(200,ok.httpStatus());
   response.set("test-private-key raw error");status.set(401);assertEquals("MODELS_AUTH_FAILED",ProviderModelCatalog.fetch(base,"test-private-key").error());
   status.set(302);assertEquals("MODELS_REDIRECT_DENIED",ProviderModelCatalog.fetch(base,"test-private-key").error());assertEquals(0,forwarded.get());
   status.set(200);for(String body:new String[]{"{}","null","{\"data\":[{\"id\":4}]}","{\"data\":[{\"id\":\"test-private-key\"}]}","{\"data\":[{\"id\":\"sk-private\"}]}"}){response.set(body);assertEquals("MODELS_INVALID_RESPONSE",ProviderModelCatalog.fetch(base,"test-private-key").error());}
   response.set("{\"data\":[]}");assertEquals("MODELS_EMPTY",ProviderModelCatalog.fetch(base,"test-private-key").error());
   response.set("x".repeat(ProviderModelCatalog.MAX_BYTES+1));assertFalse(ProviderModelCatalog.fetch(base,"test-private-key").error().isBlank());
   int before=calls.get();assertEquals("MODELS_KEY_REQUIRED",ProviderModelCatalog.fetch(base,"").error());assertEquals(before,calls.get());
  }finally{server.stop(0);}
 }
}
