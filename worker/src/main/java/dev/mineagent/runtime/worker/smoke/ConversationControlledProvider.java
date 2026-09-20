package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
/** Explicit localhost streaming fixture, never a production fallback. */
final class ConversationControlledProvider implements AutoCloseable {
    private final HttpServer http;private final Path game;private final boolean nativeMode,summaryMode;private final String summaryFailure;private final ObjectMapper json=new ObjectMapper();private final AtomicInteger calls=new AtomicInteger(),chatCalls=new AtomicInteger(),summaryCalls=new AtomicInteger();private final AtomicBoolean leak=new AtomicBoolean();
    ConversationControlledProvider(Path game)throws Exception{this(game,false);}
    ConversationControlledProvider(Path game,boolean nativeMode)throws Exception{this(game,nativeMode,false);}
    ConversationControlledProvider(Path game,boolean nativeMode,boolean summaryMode)throws Exception{
        this(game,nativeMode,summaryMode,"");
    }
    ConversationControlledProvider(Path game,boolean nativeMode,boolean summaryMode,String summaryFailure)throws Exception{
        ProductionJointAppearanceLauncher.validateConversationMode(Map.of("mineagent.conversationSmoke","true","mineagent.conversationSummarySmoke",Boolean.toString(summaryMode),"mineagent.conversationNativeSmoke",Boolean.toString(nativeMode),"mineagent.conversationSummaryFailureMode",summaryFailure));this.summaryFailure=summaryFailure;
        this.nativeMode=nativeMode;this.summaryMode=summaryMode;
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",x->{try{
            var body=json.readTree(x.getRequestBody().readNBytes(524289));String prompt=body.path("messages").path(0).path("content").asText();int call=calls.incrementAndGet();
            if(summaryMode&&prompt.startsWith("CONVERSATION_SUMMARY_V1\n")){
                summaryCalls.incrementAndGet();if(prompt.contains("SECRET_OTHER_VIEWER")||prompt.contains("SECRET_OTHER_AGENT")||prompt.contains("UNSENT_B_CANARY"))leak.set(true);
                Files.writeString(game.resolve("conversation-request-"+call+".json"),json.writeValueAsString(Map.of("mode","CONTROLLED_SUMMARY_NOT_REAL_MODEL","request",body,"leak",leak.get())));
                if(!summaryFailure.isEmpty()){
                    Files.writeString(game.resolve("conversation-summary-pending"),"Explicit localhost summary failure fixture: "+summaryFailure);
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);while(!Files.exists(game.resolve("conversation-summary-release"))&&System.nanoTime()<deadline)Thread.sleep(20);
                    if(!Files.exists(game.resolve("conversation-summary-release")))throw new IllegalStateException("SUMMARY_FIXTURE_RELEASE_TIMEOUT");
                }
                int status=summaryFailure.equals("provider-failure")?401:200;
                String summary=summaryFailure.equals("invalid-output")?"{\"summary\":\"first\",\"summary\":\"duplicate\"}":"{\"summary\":\"SUMMARY_ANCHOR: history only; no execution proof.\"}";
                byte[] response=status==401?json.writeValueAsBytes(Map.of("error",Map.of("code","EXPLICIT_SUMMARY_FAILURE"))):json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",summary)))));x.sendResponseHeaders(status,response.length);x.getResponseBody().write(response);x.getResponseBody().flush();if(!summaryFailure.isEmpty())Files.writeString(game.resolve("conversation-summary-response-sent"),"Local response sent after explicit fixture release.");return;
            }
            chatCalls.incrementAndGet();
            boolean a=prompt.endsWith("CURRENT_CONVERSATION_A"),b=prompt.endsWith("CURRENT_CONVERSATION_B"),n=nativeMode&&prompt.endsWith("CURRENT_CONVERSATION_NATIVE");if(!a&&!b&&!n)throw new IllegalArgumentException("FIXTURE_REQUEST_CONTEXT");
            if(prompt.contains("SECRET_OTHER_VIEWER")||prompt.contains("SECRET_OTHER_AGENT")||prompt.contains("UNSENT_B_CANARY")||b&&prompt.contains("HISTORY_USER_")||a&&!prompt.contains("PERSONA_BEFORE_A")||b&&!prompt.contains("PERSONA_AFTER_A"))leak.set(true);
            if(summaryMode&&a&&(!prompt.contains("SUMMARY_ANCHOR")||prompt.contains("HISTORY_USER_0\n")))leak.set(true);
            Files.writeString(game.resolve("conversation-request-"+call+".json"),json.writeValueAsString(Map.of("mode","CONTROLLED_LOCAL_SSE_NOT_REAL_MODEL","request",body,"leak",leak.get())));
            x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,0);var out=x.getResponseBody();
            String reply=a?"CONTROLLED_REPLY_A":n?"CONTROLLED_REPLY_NATIVE":"CONTROLLED_REPLY_B";
            out.write(("data: "+json.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",reply.substring(0,10))))))+"\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
            if(a){Files.writeString(game.resolve("conversation-a-pending"),"Controlled response paused for explicit UI switch.");long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);while(!Files.exists(game.resolve("conversation-release-a"))&&System.nanoTime()<deadline)Thread.sleep(20);if(!Files.exists(game.resolve("conversation-release-a")))throw new IllegalStateException("FIXTURE_SWITCH_TIMEOUT");}
            out.write(("data: "+json.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",reply.substring(10))))))+"\n\ndata: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
        }catch(Exception e){Files.writeString(game.resolve("conversation-provider-failure.txt"),e.getClass().getSimpleName());}finally{x.close();}});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){var values=new LinkedHashMap<String,String>(Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-conversation-fixture","provider.openai.apiKey","fixture-only","voice.output.enabled","false"));if(summaryMode){values.put("conversation.contextTokenBudget","2048");values.put("conversation.summary.inputBudget","8192");values.put("conversation.summary.maxCalls",summaryFailure.equals("budget-exhausted")?"1":"8");}if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),values),true).accepted())throw new IllegalStateException("FIXTURE_CONFIG_FAILED");}catch(Exception e){http.stop(0);throw e;}
    }
    void verify(){if(chatCalls.get()!=(summaryFailure.isEmpty()?(nativeMode?3:2):1)||summaryMode&&(summaryCalls.get()<1||summaryCalls.get()>(summaryFailure.isEmpty()?8:1))||!summaryMode&&summaryCalls.get()!=0||leak.get())throw new IllegalStateException("CONVERSATION_PROVIDER_PROOF_FAILED");}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("conversation-provider.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_LOCAL_SSE_NOT_REAL_MODEL","summaryFailureMode",summaryFailure,"calls",calls.get(),"chatCalls",chatCalls.get(),"summaryCalls",summaryCalls.get(),"scopeLeak",leak.get(),"paidCalls",0)));}
}
