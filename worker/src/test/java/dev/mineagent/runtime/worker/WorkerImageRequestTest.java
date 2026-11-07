package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.api.model.ModelImage;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkerImageRequestTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    final List<WorkerRequestHandler> opened=new ArrayList<>();
    WorkerRequestHandler worker(){var handler=new WorkerRequestHandler();opened.add(handler);var configured=handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",root.resolve(UUID.randomUUID().toString()).resolve("content").toString())));assertEquals("storage.configured",configured.type());return handler;}
    @org.junit.jupiter.api.AfterEach void closeWorkers(){opened.forEach(WorkerRequestHandler::close);}

    @Test void explicitOnceTextRequestDoesNotRetryProviderFailure()throws Exception{
        var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",e->{count.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(429,-1);e.close();});server.start();
        try{var h=worker();h.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind","openai-compatible","baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","model","chosen")));
            var r=h.handle(new WorkerEnvelope(1,UUID.randomUUID(),"model.completeOnce",Map.of("capability","CODING","prompt","patch")));
            assertEquals("MODEL_REQUEST_FAILED",r.payload().get("code"));assertEquals("HTTP_429",r.payload().get("message"));assertEquals(1,count.get());
        }finally{server.stop(0);}
    }
    byte[] png(){return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII=");}
    @Test void workerCarriesImagesAndDoesNotRetryOrFallBackToAnotherProviderOnImageRejection()throws Exception{
        var first=new AtomicInteger();var fallback=new AtomicInteger();var requestBody=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{first.incrementAndGet();requestBody.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));e.sendResponseHeaders(400,-1);e.close();});
        server.createContext("/api/chat",e->{fallback.incrementAndGet();e.getRequestBody().readAllBytes();byte[] b="{\"message\":{\"content\":\"fallback\"}}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        try{
            var handler=worker();String url="http://127.0.0.1:"+server.getAddress().getPort();
            for(String kind:List.of("openai-compatible","ollama"))handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind",kind,"baseUrl",url+(kind.equals("ollama")?"/":"/v1/"),"model","requested-model","apiKey","secret")));
            var payload=Map.<String,Object>of("capability","PLANNING","prompt","看图","modelCapabilities",Map.of("vision","YES"),"images",WorkerModelImages.encode(List.of(new ModelImage("image/png",png()))));
            var response=handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"model.complete",payload));
            assertEquals("MODEL_IMAGE_REQUEST_FAILED",response.payload().get("code"));assertEquals("HTTP_400",response.payload().get("message"));assertEquals(1,first.get());assertEquals(0,fallback.get());
            assertTrue(requestBody.get().contains("\"image_url\""));assertFalse(response.toString().contains("iVBOR"));assertFalse(response.toString().contains("secret"));
        }finally{server.stop(0);}
    }
    @Test void malformedImagePayloadCannotBecomeTextOnly(){
        assertThrows(IllegalArgumentException.class,()->WorkerModelImages.decode("image"));
        assertThrows(IllegalArgumentException.class,()->WorkerModelImages.decode(List.of(Map.of("mimeType","image/png","base64","invalid"))));
        var encoded=WorkerModelImages.encode(List.of(new ModelImage("image/png",png())));assertArrayEquals(png(),WorkerModelImages.decode(encoded).getFirst().bytes());
    }
    List<ModelImage> differentPixels()throws Exception{
        var source=new java.awt.image.BufferedImage(8,4,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<4;y++)for(int x=0;x<8;x++)source.setRGB(x,y,x<4?0xff0000:0x0000ff);
        var encoded=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(source,"png",encoded);source.flush();
        return List.of(dev.mineagent.runtime.worker.resources.ResourceImages.prepare(encoded.toByteArray(),List.of(0,0,4,4),"high").image(),dev.mineagent.runtime.worker.resources.ResourceImages.prepare(encoded.toByteArray(),List.of(4,0,4,4),"high").image());
    }
    @Test void multipleCroppedImagesReachBothProviderWiresInOrder()throws Exception{
        var images=differentPixels();assertFalse(Arrays.equals(images.get(0).bytes(),images.get(1).bytes()));
        var received=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(String path:List.of("/v1/chat/completions","/api/chat"))server.createContext(path,e->{received.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] body=(path.equals("/api/chat")?"{\"model\":\"visual-test\",\"message\":{\"content\":\"transport fixture\"}}":"{\"model\":\"visual-test\",\"choices\":[{\"message\":{\"content\":\"transport fixture\"}}]}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try{for(String kind:List.of("openai-compatible","ollama")){
            var handler=worker();String url="http://127.0.0.1:"+server.getAddress().getPort();
            handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind",kind,"baseUrl",url+(kind.equals("ollama")?"/":"/v1/"),"model","visual-test")));
            var response=handler.handle(new WorkerEnvelope(1,UUID.randomUUID(),"model.complete",Map.of("capability","PLANNING","prompt","比较红色和蓝色局部","modelCapabilities",Map.of("vision","YES"),"images",WorkerModelImages.encode(images))));
            assertEquals("model.result",response.type());var message=new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get()).path("messages").get(0);
            for(int n=0;n<2;n++){String base64=kind.equals("ollama")?message.path("images").get(n).asText():message.path("content").get(n+1).path("image_url").path("url").asText().split(",",2)[1];assertArrayEquals(images.get(n).bytes(),Base64.getDecoder().decode(base64));}
            assertEquals(2,kind.equals("ollama")?message.path("images").size():message.path("content").size()-1);
            assertFalse(response.toString().contains("iVBOR"));
        }}finally{server.stop(0);}
    }
    @Test void layeredStreamingAndNonStreamingKeepSystemTextAndAllImageParts()throws Exception{
        var images=differentPixels();var received=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{String raw=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);received.set(raw);boolean stream=new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw).path("stream").asBoolean();String response=stream?"data: {\"model\":\"visual-test\",\"choices\":[{\"delta\":{\"content\":\"transport fixture\"}}]}\n\ndata: [DONE]\n\n":"{\"model\":\"visual-test\",\"choices\":[{\"message\":{\"content\":\"transport fixture\"}}]}";byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try{
            var provider=new dev.mineagent.runtime.worker.provider.OpenAiCompatibleProvider(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/"),"","visual-test",java.time.Duration.ofSeconds(5));
            var request=new dev.mineagent.runtime.api.model.ModelRequest(dev.mineagent.runtime.api.model.ModelCapability.PLANNING,"fallback text",images);
            List<Map<String,Object>> messages=List.of(Map.of("role","system","content","stable application rules"),Map.of("role","user","content","compare these crops"));
            for(boolean stream:List.of(false,true)){
                if(stream)provider.streamWithTools(request,List.of(),List.of(),messages,delta->{},thinking->{});else provider.completeWithTools(request,List.of(),messages);
                var wire=new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get()).path("messages");assertEquals("stable application rules",wire.get(0).path("content").asText());assertEquals(2,wire.size());
                var parts=wire.get(1).path("content");assertEquals(3,parts.size());assertEquals("compare these crops",parts.get(0).path("text").asText());
                for(int n=0;n<2;n++)assertArrayEquals(images.get(n).bytes(),Base64.getDecoder().decode(parts.get(n+1).path("image_url").path("url").asText().split(",",2)[1]));
                assertEquals("compare these crops",messages.get(1).get("content"));
            }
        }finally{server.stop(0);}
    }

}
