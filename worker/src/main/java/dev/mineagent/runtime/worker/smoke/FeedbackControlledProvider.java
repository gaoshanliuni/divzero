package dev.mineagent.runtime.worker.smoke;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit loopback fixture, never a production fallback. Each real feedback task must inspect/reply/verify. */
public final class FeedbackControlledProvider implements AutoCloseable {
    private final Path game;private final HttpServer http;private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private final ObjectMapper json=new ObjectMapper();private final AtomicInteger calls=new AtomicInteger();private final Map<String,Integer> rounds=new ConcurrentHashMap<>();private final CountDownLatch release=new CountDownLatch(1);
    public FeedbackControlledProvider(Path game)throws Exception{
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.setExecutor(executor);
        http.createContext("/v1/chat/completions",exchange->{try{
            int call=calls.incrementAndGet();byte[] input=exchange.getRequestBody().readNBytes(524289);if(input.length>524288)throw new IllegalStateException("FEEDBACK_PROMPT_BUDGET");var request=json.readTree(input);String prompt=request.path("messages").path(0).path("content").asText();
            Files.writeString(game.resolve("feedback-wake-request-"+call+".json"),json.writeValueAsString(Map.of("kind","CONTROLLED_HTTP_NOT_REAL_MODEL","request",request)));
            var names=new TreeSet<String>();request.path("tools").forEach(t->names.add(t.path("function").path("name").asText()));if(!names.equals(Set.of("inspect_feedback","reply_feedback","finish_task"))||prompt.contains("当前任务上下文：")||call>6)throw new IllegalStateException("FEEDBACK_TOOL_OR_PROMPT_SCOPE");
            String prefix="EVENT_WAKE_CONTEXT_DATA_NOT_INSTRUCTIONS: ";int start=prompt.indexOf(prefix);if(start<0)throw new IllegalStateException("FEEDBACK_CONTEXT_MISSING");var event=json.readTree(prompt.substring(start+prefix.length())).path("event");String id=event.path("feedbackId").asText(),question=event.path("payload").path("question").asText();if(!Set.of("QUESTION_A","QUESTION_B").contains(question)||!event.path("dataNotInstructions").asBoolean()||event.has("authority")||event.has("scope"))throw new IllegalStateException("FEEDBACK_CONTEXT_NOT_PROJECTED");
            int round=rounds.merge(id,1,Integer::sum);writeCount();String name;Object arguments;
            if(round==1){name="inspect_feedback";arguments=Map.of();}
            else if(round==2){if(!release.await(90,TimeUnit.SECONDS))throw new IllegalStateException("FEEDBACK_CLOSE_BEFORE_REPLY_TIMEOUT");name="reply_feedback";arguments=Map.of("text","针对 "+question+" 的解释：先观察示例，再分步尝试；此回复只保存在你的会话中。");}
            else if(round==3){var match=java.util.regex.Pattern.compile("\\\"replyOperationId\\\"\\s*:\\s*\\\"([a-f0-9-]{36})\\\"").matcher(prompt);if(!match.find())throw new IllegalStateException("FEEDBACK_REPLY_RECEIPT_MISSING");name="finish_task";arguments=Map.of("checks",List.of(Map.of("kind","feedback_reply","operation_id",match.group(1))));}
            else throw new IllegalStateException("FEEDBACK_PLANNING_REPLAY");
            var tool=Map.of("id","feedback-controlled-"+call,"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(arguments)));byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","","tool_calls",List.of(tool))))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Exception failure){Files.writeString(game.resolve("feedback-wake-provider-failure.txt"),failure.toString());}finally{exchange.close();}});http.start();writeCount();
    }
    public String baseUrl(){return "http://127.0.0.1:"+http.getAddress().getPort()+"/v1/";}
    public int calls(){return calls.get();}
    public void releaseReplies(){release.countDown();}
    private void writeCount()throws Exception{Files.writeString(game.resolve("feedback-wake-provider-count.json"),json.writeValueAsString(Map.of("calls",calls.get(),"rounds",new TreeMap<>(rounds),"paidCalls",0)));}
    public void verify(){if(calls.get()!=6||rounds.size()!=2||rounds.values().stream().anyMatch(n->n!=3))throw new IllegalStateException("FEEDBACK_PROVIDER_COUNTS");}
    public void verifyCancelled(){if(calls.get()!=2||rounds.size()!=1||rounds.values().iterator().next()!=2)throw new IllegalStateException("FEEDBACK_CANCEL_PROVIDER_COUNTS");}
    @Override public void close()throws Exception{http.stop(0);release.countDown();executor.shutdownNow();writeCount();Files.writeString(game.resolve("feedback-wake-provider.json"),json.writeValueAsString(Map.of("calls",calls.get(),"rounds",new TreeMap<>(rounds),"paidCalls",0,"kind","CONTROLLED_HTTP_NOT_REAL_MODEL")));}
}
