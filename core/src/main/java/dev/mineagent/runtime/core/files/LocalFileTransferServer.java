package dev.mineagent.runtime.core.files;
import com.sun.net.httpserver.*;import com.fasterxml.jackson.databind.*;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.security.SecureRandom;import java.util.*;import java.util.concurrent.*;

/** Port 25510 loopback transfer, bearer capability scoped to a selected world/player. No path or shell API. */
public final class LocalFileTransferServer implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();private final HttpServer server;private final ExecutorService executor;private final FileLibrary store;
    private record Session(UUID owner,String origin,long expires){}private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    public LocalFileTransferServer(FileLibrary store,int port)throws IOException{this.store=store;server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);server.createContext("/",this::serve);server.start();}
    public int port(){return server.getAddress().getPort();}
    public Map<String,Object> session(UUID owner,String origin){sessions.values().removeIf(s->s.expires<System.currentTimeMillis());if(sessions.size()>64)throw new IllegalStateException("BUILDING_SESSION_BUSY");if(!origin.isEmpty()&&!origin.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IllegalArgumentException("BUILDING_SESSION_ORIGIN");byte[] b=new byte[32];new SecureRandom().nextBytes(b);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(b);sessions.put(token,new Session(owner,origin,System.currentTimeMillis()+3600000));return Map.of("url","http://127.0.0.1:"+port(),"token",token,"maxBytes",FileLibrary.MAX_ASSET_BYTES);}
    private void json(HttpExchange x,int code,Object result)throws IOException{byte[] data=JSON.writeValueAsBytes(result);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(code,data.length);x.getResponseBody().write(data);}
    private JsonNode body(HttpExchange x)throws IOException{byte[] b=x.getRequestBody().readNBytes(16385);if(b.length>16384)throw new IllegalArgumentException("BUILDING_REQUEST_SIZE");return JSON.readTree(b);}
    private void serve(HttpExchange x)throws IOException{try(x){try{
        var h=x.getResponseHeaders();h.set("Cache-Control","no-store");h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");String expected="127.0.0.1:"+port();if(!x.getRemoteAddress().getAddress().isLoopbackAddress()||!expected.equals(x.getRequestHeaders().getFirst("Host"))){json(x,403,Map.of("error","BUILDING_HOST"));return;}
        String auth=x.getRequestHeaders().getFirst("Authorization");Session session=auth!=null&&auth.startsWith("Bearer ")?sessions.get(auth.substring(7)):null;if(session==null||session.expires<System.currentTimeMillis()){json(x,401,Map.of("error","BUILDING_SESSION_EXPIRED"));return;}
        String origin=x.getRequestHeaders().getFirst("Origin");if(origin!=null&&!origin.equals(session.origin)){json(x,403,Map.of("error","BUILDING_ORIGIN"));return;}if(origin!=null)h.set("Access-Control-Allow-Origin",origin);
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();if(x.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException("BUILDING_QUERY_NOT_ALLOWED");UUID owner=session.owner;
        if(method.equals("POST")&&path.equals("/list")){var b=body(x);json(x,200,store.list(owner,b.path("query").asText(""),b.path("offset").asInt(0)));return;}
        if(method.equals("POST")&&path.equals("/begin")){var b=body(x);json(x,200,Map.of("upload",store.begin(owner,b.path("name").asText(),b.path("kind").asText())));return;}
        if(method.equals("POST")&&path.equals("/directory")){var b=body(x);store.directory(owner,UUID.fromString(b.path("upload").asText()),b.path("path").asText());json(x,200,Map.of("status","CREATED"));return;}
        if(method.equals("POST")&&path.equals("/file")){var b=body(x);json(x,200,Map.of("part",store.beginFile(owner,UUID.fromString(b.path("upload").asText()),b.path("path").asText(),b.path("bytes").asLong(-1))));return;}
        if(method.equals("PUT")&&path.startsWith("/chunk/")){var p=path.split("/");if(p.length!=5)throw new IllegalArgumentException("BUILDING_CHUNK_URL");byte[] data=x.getRequestBody().readNBytes(1024*1024+1);json(x,200,Map.of("offset",store.chunk(owner,UUID.fromString(p[2]),UUID.fromString(p[3]),Long.parseLong(p[4]),data)));return;}
        if(method.equals("POST")&&path.equals("/finish")){var b=body(x);json(x,200,store.finish(owner,UUID.fromString(b.path("upload").asText()),"LOCAL_UPLOAD"));return;}
        if(method.equals("POST")&&path.equals("/cancel")){var b=body(x);store.cancel(owner,UUID.fromString(b.path("upload").asText()));json(x,200,Map.of("status","CANCELLED"));return;}
        if(method.equals("GET")&&path.startsWith("/asset/")){json(x,200,store.asset(owner,UUID.fromString(path.substring(7))));return;}
        if(method.equals("GET")&&path.startsWith("/download/")){UUID id=UUID.fromString(path.substring(10));var a=store.asset(owner,id);h.set("Content-Type","application/octet-stream");h.set("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode(a.name()+(!a.kind().equals("FILE")?".zip":""),StandardCharsets.UTF_8).replace("+","%20"));x.sendResponseHeaders(200,0);store.archive(owner,id,x.getResponseBody());return;}
        json(x,404,Map.of("error","BUILDING_ROUTE"));
    }catch(Exception e){String code=e.getMessage();try{json(x,400,Map.of("error",code!=null&&code.matches("BUILDING_[A-Z_]+")?code:"BUILDING_TRANSFER_FAILED"));}catch(IOException ignored){}}}}
    @Override public void close(){sessions.clear();server.stop(0);executor.shutdownNow();}
}
