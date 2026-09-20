package dev.mineagent.runtime.worker.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mineagent.runtime.api.model.ModelCapability;
import dev.mineagent.runtime.api.model.ModelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpModelProviderTest {
    @Test void explicitDeepSeekHighThinkingHasNoOutputTokenCapAndDoesNotLeakToOtherProviders(){
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var body=json.createObjectNode();OpenAiCompatibleProvider.configureConversationThinking(URI.create("https://api.deepseek.com/v1/"),"deepseek-flash",body);
        assertEquals("high",body.path("reasoning_effort").asText());assertEquals("enabled",body.path("thinking").path("type").asText());assertFalse(body.has("max_tokens"));
        var other=json.createObjectNode();OpenAiCompatibleProvider.configureConversationThinking(URI.create("https://example.com/v1/"),"deepseek-flash",other);assertTrue(other.isEmpty());
    }

    @Test void reasoningIsRetainedForToolContinuationButNeverEmittedAsChat()throws Exception{
        var received=new AtomicReference<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions",e->{received.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String response=calls.incrementAndGet()==1?
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"transport-only\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"visible\",\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"inspect_player\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n":
                "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\ndata: [DONE]\n\n";
            respond(e,200,response);
        });
        var provider=new OpenAiCompatibleProvider(baseUri.resolve("/v1/"),"secret","deepseek-flash",Duration.ofSeconds(2));var chunks=new java.util.ArrayList<String>();var definitions=java.util.List.of(new ToolDefinition("inspect_player","read","{\"type\":\"object\"}"));
        var request=new ModelRequest(ModelCapability.SEMANTIC,"x");var first=provider.streamWithTools(request,definitions,java.util.List.of(),chunks::add);
        assertEquals(java.util.List.of("visible"),chunks);assertEquals("transport-only",first.reasoningContent());assertEquals("visible",first.text());
        provider.streamWithTools(request,definitions,java.util.List.of(java.util.Map.of("role","assistant","content",first.text(),"reasoning_content",first.reasoningContent(),"tool_calls",java.util.List.of())),chunks::add);
        var wire=new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get());assertEquals("transport-only",wire.path("messages").get(1).path("reasoning_content").asText());assertFalse(wire.has("max_tokens"));
    }
    @Test void lengthTerminatedToolBatchIsNotExecutable(){
        server.createContext("/v1/chat/completions",e->{e.getRequestBody().readAllBytes();respond(e,200,"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n");});
        var provider=new OpenAiCompatibleProvider(baseUri.resolve("/v1/"),"secret","test",Duration.ofSeconds(2));assertEquals("STREAM_OUTPUT_LIMIT",assertThrows(ProviderRequestException.class,()->provider.stream(new ModelRequest(ModelCapability.SEMANTIC,"x"),s->{})).getMessage());
    }
    @Test void incompleteSseRetainsDeliveredDeltasButNeverReturnsACompleteReply(){
        server.createContext("/v1/chat/completions",e->{e.getRequestBody().readAllBytes();e.getResponseHeaders().set("Content-Type","text/event-stream");respond(e,200,"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");});
        var provider=new OpenAiCompatibleProvider(baseUri.resolve("/v1/"),"secret","test",Duration.ofSeconds(2));var chunks=new java.util.ArrayList<String>();
        assertThrows(ProviderRequestException.class,()->provider.stream(new ModelRequest(ModelCapability.SEMANTIC,"x"),chunks::add));assertEquals(java.util.List.of("partial"),chunks);
    }
    @Test void nativeOllamaReceivesImageArrayRatherThanDroppingTheImage()throws Exception{
        var received=new AtomicReference<String>();server.createContext("/api/chat",e->{received.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));respond(e,200,"{\"message\":{\"content\":\"seen\"}}");});
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII=");
        var provider=new OllamaProvider(baseUri,"requested-model",Duration.ofSeconds(2));provider.complete(new ModelRequest(ModelCapability.PLANNING,"看图",java.util.List.of(new dev.mineagent.runtime.api.model.ModelImage("image/png",png))));
        assertEquals(java.util.Base64.getEncoder().encodeToString(png),new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get()).path("messages").get(0).path("images").get(0).asText());
    }
    @Test void sendsScreenshotAsActualImageContentWithoutChangingTheRequestedModel() throws Exception {
        var received=new AtomicReference<String>();server.createContext("/v1/chat/completions",exchange->{received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));respond(exchange,200,"{\"choices\":[{\"message\":{\"content\":\"seen\"}}]}");});
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII=");
        var provider=new OpenAiCompatibleProvider(baseUri.resolve("/v1/"),"secret","requested-model",Duration.ofSeconds(2));
        assertEquals("seen",provider.complete(new ModelRequest(ModelCapability.PLANNING,"看图",java.util.List.of(new dev.mineagent.runtime.api.model.ModelImage("image/png",png)))).text());
        var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get());assertEquals("requested-model",body.path("model").asText());
        var content=body.path("messages").get(0).path("content");assertTrue(content.isArray());assertEquals("text",content.get(0).path("type").asText());
        assertEquals("image_url",content.get(1).path("type").asText());assertEquals("data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(png),content.get(1).path("image_url").path("url").asText());
    }
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsOpenAiCompatibleChatCompletionsWithBearerKey() {
        var authorization = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}");
        });
        var provider = new OpenAiCompatibleProvider(
                baseUri.resolve("/v1/"), "secret", "gpt-test", Duration.ofSeconds(2));

        var response = provider.complete(new ModelRequest(ModelCapability.SEMANTIC, "打招呼"));

        assertEquals("openai-compatible", response.providerId());
        assertEquals("你好", response.text());
        assertEquals("Bearer secret", authorization.get());
    }

    @Test
    void callsNativeOllamaChatEndpoint() {
        server.createContext("/api/chat", exchange ->
                respond(exchange, 200, "{\"message\":{\"content\":\"本地回复\"},\"done\":true}"));
        var provider = new OllamaProvider(baseUri, "qwen-test", Duration.ofSeconds(2));

        var response = provider.complete(new ModelRequest(ModelCapability.CODING, "生成代码"));

        assertEquals("ollama", response.providerId());
        assertEquals("本地回复", response.text());
    }

    @Test
    void exposesHttpFailureAsProviderException() {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 429, "{\"error\":\"limited\"}"));
        var provider = new OpenAiCompatibleProvider(
                baseUri.resolve("/v1/"), "secret", "gpt-test", Duration.ofSeconds(2));

        ProviderRequestException error = assertThrows(ProviderRequestException.class,
                () -> provider.complete(new ModelRequest(ModelCapability.PLANNING, "计划")));

        assertEquals(429, error.statusCode());
    }

    @Test
    void callsOpenAiCompatibleEmbeddingsEndpoint() {
        server.createContext("/v1/embeddings", exchange ->
                respond(exchange, 200, "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}"));
        var provider = new OpenAiCompatibleProvider(
                baseUri.resolve("/v1/"), "secret", "embed-test", Duration.ofSeconds(2));

        var embedding = provider.embed("Minecraft knowledge");

        assertArrayEquals(new double[]{0.1, 0.2, 0.3}, embedding, 0.000001);
    }

    @Test
    void decodesOpenAiCompatibleImageGenerationResponse() {
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        server.createContext("/v1/images/generations", exchange ->
                respond(exchange, 200, "{\"data\":[{\"b64_json\":\"" + encoded + "\"}]}"));
        var provider = new OpenAiCompatibleProvider(
                baseUri.resolve("/v1/"), "secret", "image-test", Duration.ofSeconds(2));

        var image = provider.generateImage("方块纹理", "1024x1024");

        assertArrayEquals(new byte[]{1, 2, 3, 4}, image);
    }

    @Test
    void parsesToolCallsWithoutLosingAssistantText() {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"choices":[{"message":{"content":"准备执行","tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"place_block","arguments":"{\\"x\\":1}"}}
                ]}}]}
                """));
        var provider = new OpenAiCompatibleProvider(
                baseUri.resolve("/v1/"), "secret", "gpt-test", Duration.ofSeconds(2));

        var completion = provider.completeWithTools(
                new ModelRequest(ModelCapability.PLANNING, "放置方块"),
                java.util.List.of(new ToolDefinition("place_block", "放置方块", "{\"type\":\"object\"}")));

        assertEquals("准备执行", completion.text());
        assertEquals("place_block", completion.toolCalls().getFirst().name());
        assertEquals("{\"x\":1}", completion.toolCalls().getFirst().argumentsJson());
    }

    @Test
    void streamsOpenAiCompatibleServerSentEventsInOrder() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var provider = new OpenAiCompatibleProvider(
                baseUri.resolve("/v1/"), "secret", "gpt-test", Duration.ofSeconds(2));
        var chunks = new java.util.ArrayList<String>();

        var result = provider.stream(new ModelRequest(ModelCapability.SEMANTIC, "打招呼"), chunks::add);

        assertEquals(java.util.List.of("你", "好"), chunks);
        assertEquals("你好", result.text());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
