package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Opt-in, isolated paid-test guard. No proxy, response fixture, credential, or prompt logging. */
final class RealProviderSmokeAudit {
    static final int MAX_CALLS = 24;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path result;
    private final Map<String, Object> evidence;

    private RealProviderSmokeAudit(Path result, Map<String, Object> evidence) {
        this.result = result;
        this.evidence = evidence;
    }

    static Path configuredDirectory() {
        String value = System.getenv("MINEAGENT_REAL_PROVIDER_AUDIT_DIR");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    static RealProviderSmokeAudit begin(URI uri, ObjectNode body) throws Exception {
        Path directory = configuredDirectory();
        return directory == null ? null : begin(directory, uri, body, parseBudget(System.getenv().getOrDefault("MINEAGENT_REAL_PROVIDER_MAX_CALLS", "24")),"true".equals(System.getenv("MINEAGENT_REAL_AGENT_MODEL_TEST")));
    }

    static int parseBudget(String value) { return "unlimited".equals(value) ? -1 : Integer.parseInt(value); }

    static RealProviderSmokeAudit begin(Path directory, URI uri, ObjectNode body) throws Exception {
        return begin(directory,uri,body,MAX_CALLS);
    }
    static synchronized RealProviderSmokeAudit begin(Path directory, URI uri, ObjectNode body, int maximum) throws Exception {return begin(directory,uri,body,maximum,false);}
    private static synchronized RealProviderSmokeAudit begin(Path directory, URI uri, ObjectNode body, int maximum,boolean agentModels) throws Exception {
        if(maximum != -1 && (maximum<1||maximum>MAX_CALLS))throw new IllegalArgumentException("REAL_PROVIDER_SMOKE_CALL_LIMIT");
        if (!uri.equals(URI.create("https://api.deepseek.com/v1/chat/completions"))
                || !(agentModels?Set.of("deepseek-flash","deepseek-v4-pro"):Set.of("deepseek-flash")).contains(body.path("model").asText()) || !body.path("stream").asBoolean())
            throw new IllegalArgumentException("REAL_PROVIDER_SMOKE_ENDPOINT_OR_MODE");
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("REAL_PROVIDER_SMOKE_DIRECTORY");
        int index = 1;
        while (Files.exists(directory.resolve(index + "-started.json"))) {
            Path done = directory.resolve(index + "-completed.json");
            if (!Files.isRegularFile(done) || !JSON.readTree(done.toFile()).path("completed").asBoolean())
                throw new IllegalStateException("REAL_PROVIDER_SMOKE_PREVIOUS_OUTCOME_UNKNOWN");
            index++;
        }
        if (maximum != -1 && index > maximum) throw new IllegalStateException("REAL_PROVIDER_SMOKE_CALL_BUDGET");
        // The user explicitly disabled assistant-imposed output token caps. Provider limits still apply.
        body.putObject("stream_options").put("include_usage", true);
        byte[] request = JSON.writeValueAsBytes(body);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("call", index);
        evidence.put("callBudget", maximum == -1 ? "USER_AUTHORIZED_UNLIMITED" : maximum);
        evidence.put("endpoint", uri.toString());
        evidence.put("requestedModel", body.path("model").asText());
        evidence.put("requestBytes", request.length);
        evidence.put("requestSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request)));
        evidence.put("outputLimitMode", "PROVIDER_DEFAULT_NOT_SET");
        evidence.put("thinkingEnabled", "enabled".equals(body.path("thinking").path("type").asText()));
        evidence.put("reasoningEffort",body.path("reasoning_effort").asText(""));
        evidence.put("highReasoningEffort", "high".equals(body.path("reasoning_effort").asText()));
        evidence.put("startedAt", java.time.Instant.now().toString());
        // CREATE_NEW fences concurrent processes and preserves consumed budget after worker restart.
        Files.writeString(directory.resolve(index + "-started.json"), JSON.writeValueAsString(evidence), StandardOpenOption.CREATE_NEW);
        return new RealProviderSmokeAudit(directory.resolve(index + "-completed.json"), evidence);
    }

    void failed(Throwable failure){
        try{
            var record=new LinkedHashMap<String,Object>(evidence);String code=Objects.toString(failure.getMessage(),"");
            record.put("completed",false);record.put("errorType",failure.getClass().getSimpleName());record.put("errorCode",code.matches("(?:TOOL_STREAM|STREAM|PROVIDER)_[A-Z_]{1,60}")?code:"STREAM_OUTCOME_UNKNOWN");
            if(failure instanceof ProviderRequestException provider)record.put("httpStatus",provider.statusCode());
            record.put("finishedAt",java.time.Instant.now().toString());Files.writeString(result.resolveSibling(result.getFileName().toString().replace("-completed.json","-failed.json")),JSON.writeValueAsString(record),StandardOpenOption.CREATE_NEW);
        }catch(Exception ignored){/* Never retry a paid request to repair diagnostics. The start record remains fenced. */}
    }
    void complete(String responseModel, JsonNode usage, List<ToolCall> calls, String text) throws Exception {
        complete(responseModel,usage,calls,text,-1);
    }
    void complete(String responseModel, JsonNode usage, List<ToolCall> calls, String text,int thinkingChars) throws Exception {
        if(thinkingChars>=0)evidence.put("thinkingChars",thinkingChars);
        evidence.put("completed", true);
        evidence.put("responseModel", responseModel);
        evidence.put("toolNames", calls.stream().map(ToolCall::name).toList());
        evidence.put("textChars", text.length());
        var tokens = new LinkedHashMap<String, Long>();
        for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens"))
            if (usage != null && usage.path(key).isIntegralNumber()) tokens.put(key, usage.path(key).asLong());
        evidence.put("usage", tokens);
        evidence.put("finishedAt", java.time.Instant.now().toString());
        Files.writeString(result, JSON.writeValueAsString(evidence), StandardOpenOption.CREATE_NEW);
    }
}
