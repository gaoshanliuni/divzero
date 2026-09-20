package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class ComfyUiProvider {
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private final URI baseUri;
    private final JsonNode workflowTemplate;
    private final Duration timeout;
    private final Duration pollInterval;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComfyUiProvider(URI baseUri, String workflowJson, Duration timeout, Duration pollInterval) {
        if (baseUri == null || workflowJson == null || workflowJson.isBlank()
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("invalid ComfyUI configuration");
        }
        try {
            this.workflowTemplate = mapper.readTree(workflowJson);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid ComfyUI workflow JSON", invalid);
        }
        this.baseUri = normalize(baseUri);
        this.timeout = timeout;
        this.pollInterval = pollInterval;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public byte[] generate(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 16_384) {
            throw new IllegalArgumentException("invalid ComfyUI prompt");
        }
        try {
            JsonNode workflow = workflowTemplate.deepCopy();
            replacePrompt(workflow, prompt);
            ObjectNode body = mapper.createObjectNode();
            body.set("prompt", workflow);
            body.put("client_id", UUID.randomUUID().toString());
            JsonNode submitted = postJson("prompt", body);
            String promptId = submitted.path("prompt_id").asText("");
            if (promptId.isBlank()) {
                throw new ProviderRequestException(0, "ComfyUI response missing prompt_id");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode history = getJson("history/" + encode(promptId));
                JsonNode completed = history.path(promptId);
                ImageRef image = findImage(completed.path("outputs"));
                if (image != null) {
                    return download(image);
                }
                Thread.sleep(Math.max(1, pollInterval.toMillis()));
            }
            throw new ProviderRequestException(0, "ComfyUI generation timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderRequestException("ComfyUI generation interrupted", interrupted);
        } catch (ProviderRequestException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProviderRequestException("ComfyUI generation failed", failure);
        }
    }

    private JsonNode postJson(String path, JsonNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private byte[] download(ImageRef image) throws Exception {
        String query = "view?filename=" + encode(image.filename())
                + "&subfolder=" + encode(image.subfolder()) + "&type=" + encode(image.type());
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(baseUri.resolve(query))
                        .timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || response.body().length == 0 || response.body().length > MAX_IMAGE_BYTES) {
            throw new ProviderRequestException(response.statusCode(), "ComfyUI image download failed");
        }
        return response.body();
    }

    private static ImageRef findImage(JsonNode outputs) {
        if (!outputs.isObject()) {
            return null;
        }
        for (var field : outputs.properties()) {
            JsonNode images = field.getValue().path("images");
            if (images.isArray() && !images.isEmpty()) {
                JsonNode image = images.get(0);
                String filename = image.path("filename").asText("");
                if (!filename.isBlank()) {
                    return new ImageRef(filename, image.path("subfolder").asText(""),
                            image.path("type").asText("output"));
                }
            }
        }
        return null;
    }

    private static void replacePrompt(JsonNode node, String prompt) {
        if (node instanceof ObjectNode object) {
            for (var field : java.util.List.copyOf(object.properties())) {
                JsonNode value = field.getValue();
                if (value.isTextual() && value.asText().contains("${prompt}")) {
                    object.set(field.getKey(), new TextNode(value.asText().replace("${prompt}", prompt)));
                } else {
                    replacePrompt(value, prompt);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (value.isTextual() && value.asText().contains("${prompt}")) {
                    array.set(index, new TextNode(value.asText().replace("${prompt}", prompt)));
                } else {
                    replacePrompt(value, prompt);
                }
            }
        }
    }

    private static void ensureSuccess(int status, String body) {
        if (status < 200 || status >= 300) {
            throw new ProviderRequestException(status, "ComfyUI HTTP " + status + ": " + body);
        }
    }

    private static URI normalize(URI uri) {
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record ImageRef(String filename, String subfolder, String type) {
    }
}
