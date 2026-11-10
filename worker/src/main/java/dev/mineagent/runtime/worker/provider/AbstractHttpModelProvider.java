package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.model.ModelCapability;
import dev.mineagent.runtime.api.model.ModelProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

abstract class AbstractHttpModelProvider implements ModelProvider {
    protected final URI baseUri;
    protected final String model;
    protected final Duration timeout;
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final HttpClient client;

    AbstractHttpModelProvider(URI baseUri, String model, Duration timeout) {
        this.baseUri = baseUri;
        this.model = model;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return EnumSet.of(
                ModelCapability.SEMANTIC,
                ModelCapability.PLANNING,
                ModelCapability.CODING,
                ModelCapability.VISION
        );
    }

    protected JsonNode post(URI uri, JsonNode body, String bearerToken) {
        RealProviderSmokeAudit audit=null;
        try {
            if(RealProviderSmokeAudit.configuredDirectory()!=null){if(!(body instanceof com.fasterxml.jackson.databind.node.ObjectNode object))throw new IllegalArgumentException("REAL_PROVIDER_SMOKE_BODY");audit=RealProviderSmokeAudit.begin(uri,object);}
            var request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (bearerToken != null && !bearerToken.isBlank()) {
                request.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = "PROVIDER_REJECTED";
                try {
                    String candidate = mapper.readTree(response.body()).path("error").path("code").asText("");
                    if (candidate.matches("[A-Za-z0-9_.-]{1,80}") && !candidate.startsWith("sk-")
                            && (bearerToken == null || !candidate.contains(bearerToken))) code = candidate;
                } catch (Exception ignored) { /* Error bodies are not safe logs; retain only HTTP status. */ }
                throw new ProviderRequestException(response.statusCode(),
                        id() + " HTTP " + response.statusCode() + ": " + code);
            }
            var parsed=mapper.readTree(response.body());
            if(audit!=null){var message=parsed.path("choices").path(0).path("message");var calls=new java.util.ArrayList<ToolCall>();for(var call:message.path("tool_calls"))calls.add(new ToolCall(call.path("id").asText(),call.path("function").path("name").asText(),call.path("function").path("arguments").asText()));audit.complete(parsed.path("model").asText(),parsed.path("usage"),calls,message.path("content").asText(),message.path("reasoning_content").asText().length());}
            return parsed;
        } catch (InterruptedException interrupted) {
            if(audit!=null)audit.failed(interrupted);Thread.currentThread().interrupt();
            throw new ProviderRequestException(id() + " 请求被中断", interrupted);
        } catch (ProviderRequestException failure) {
            if(audit!=null)audit.failed(failure);throw failure;
        } catch (Exception failure) {
            if(audit!=null)audit.failed(failure);throw new ProviderRequestException(id() + " 请求失败", failure);
        }
    }

    protected static String requiredText(JsonNode node, String description) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ProviderRequestException(0, "响应缺少 " + description);
        }
        return node.asText();
    }
}
