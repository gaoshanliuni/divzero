package dev.mineagent.runtime.worker.provider;

import dev.mineagent.runtime.api.model.ModelRequest;
import dev.mineagent.runtime.api.model.ModelResponse;

import java.net.URI;
import java.time.Duration;

public final class OllamaProvider extends AbstractHttpModelProvider {
    public OllamaProvider(URI baseUri, String model, Duration timeout) {
        super(baseUri, model, timeout);
    }

    @Override
    public String id() {
        return "ollama";
    }
    public OllamaProvider withTimeout(Duration timeout){return new OllamaProvider(baseUri,model,timeout);}

    @Override
    public ModelResponse complete(ModelRequest request) {
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        var message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", request.prompt());
        if(!request.images().isEmpty()){var images=message.putArray("images");for(var image:request.images())images.add(java.util.Base64.getEncoder().encodeToString(image.bytes()));}
        var json = post(baseUri.resolve("/api/chat"), body, null);
        String text = requiredText(json.path("message").path("content"), "message.content");
        return new ModelResponse(id(), text, model, json.path("model").asText(""));
    }
}
