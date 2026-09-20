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
        try {
            if(RealProviderSmokeAudit.configuredDirectory()!=null) throw new IllegalStateException("REAL_PROVIDER_SMOKE_STREAM_ONLY");
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
            return mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderRequestException(id() + " 请求被中断", interrupted);
        } catch (ProviderRequestException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProviderRequestException(id() + " 请求失败", failure);
        }
    }

    protected static String requiredText(JsonNode node, String description) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ProviderRequestException(0, "响应缺少 " + description);
        }
        return node.asText();
    }
}
