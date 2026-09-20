package dev.mineagent.runtime.worker.provider;

import dev.mineagent.runtime.api.model.ModelRequest;
import dev.mineagent.runtime.api.model.ModelResponse;

import java.net.URI;
import java.time.Duration;

public final class OpenAiCompatibleProvider extends AbstractHttpModelProvider {
    private final String apiKey;

    public OpenAiCompatibleProvider(URI baseUri, String apiKey, String model, Duration timeout) {
        super(normalize(baseUri), model, timeout);
        this.apiKey = apiKey;
    }

    @Override
    public String id() {
        return "openai-compatible";
    }
    public OpenAiCompatibleProvider withTimeout(Duration timeout){return new OpenAiCompatibleProvider(baseUri,apiKey,model,timeout);}

    @Override
    public ModelResponse complete(ModelRequest request) {
        if(request.capability()==dev.mineagent.runtime.api.model.ModelCapability.CODING&&officialDeepSeek())return stream(request,ignored->{});
        ToolCompletion completion = completeWithTools(request, java.util.List.of());
        if (completion.text().isBlank()) {
            throw new ProviderRequestException(0, "响应缺少 choices[0].message.content");
        }
        return new ModelResponse(id(), completion.text(), completion.requestedModel(), completion.responseModel());
    }

    public ToolCompletion completeWithTools(ModelRequest request, java.util.List<ToolDefinition> tools) {
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        var message = body.putArray("messages").addObject();
        message.put("role", "user");
        content(message,request);
        if (!tools.isEmpty()) {
            var toolArray = body.putArray("tools");
            for (ToolDefinition tool : tools) {
                var encoded = toolArray.addObject();
                encoded.put("type", "function");
                var function = encoded.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                try {
                    function.set("parameters", mapper.readTree(tool.parametersJson()));
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("invalid tool parameters JSON", invalid);
                }
            }
        }
        var json = post(baseUri.resolve("chat/completions"), body, apiKey);
        var responseMessage = json.path("choices").path(0).path("message");
        String text = responseMessage.path("content").asText("");
        var calls = new java.util.ArrayList<ToolCall>();
        for (var call : responseMessage.path("tool_calls")) {
            String callId = call.path("id").asText("");
            String name = call.path("function").path("name").asText("");
            String arguments = call.path("function").path("arguments").asText("");
            if (!name.isBlank() && !arguments.isBlank()) {
                calls.add(new ToolCall(callId, name, arguments));
            }
        }
        if (text.isBlank() && calls.isEmpty()) {
            throw new ProviderRequestException(0, "响应缺少 assistant content/tool_calls");
        }
        return new ToolCompletion(text, calls, model, responseModel(json));
    }

    public double[] embed(String input) {
        if (input == null || input.isBlank() || input.length() > 32_768) {
            throw new IllegalArgumentException("invalid embedding input");
        }
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", input);
        var json = post(baseUri.resolve("embeddings"), body, apiKey);
        var values = json.path("data").path(0).path("embedding");
        if (!values.isArray() || values.isEmpty() || values.size() > 65_536) {
            throw new ProviderRequestException(0, "响应缺少 data[0].embedding");
        }
        double[] result = new double[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index).asDouble(Double.NaN);
            if (!Double.isFinite(result[index])) {
                throw new ProviderRequestException(0, "embedding 包含无效数值");
            }
        }
        return result;
    }

    public byte[] generateImage(String prompt, String size) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 16_384
                || size == null || !size.matches("[0-9]{2,5}x[0-9]{2,5}")) {
            throw new IllegalArgumentException("invalid image request");
        }
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", size);
        body.put("response_format", "b64_json");
        var json = post(baseUri.resolve("images/generations"), body, apiKey);
        String encoded = requiredText(json.path("data").path(0).path("b64_json"), "data[0].b64_json");
        if (encoded.length() > 32 * 1024 * 1024) {
            throw new ProviderRequestException(0, "image response exceeds limit");
        }
        try {
            byte[] result = java.util.Base64.getDecoder().decode(encoded);
            if (result.length == 0) {
                throw new IllegalArgumentException("empty image");
            }
            return result;
        } catch (IllegalArgumentException invalid) {
            throw new ProviderRequestException("invalid image base64", invalid);
        }
    }

    public ModelResponse stream(ModelRequest request, java.util.function.Consumer<String> chunkConsumer) {
        var result=streamWithTools(request,java.util.List.of(),java.util.List.of(),chunkConsumer);return new ModelResponse(id(),result.text(),result.requestedModel(),result.responseModel());
    }

    public ToolCompletion streamWithTools(ModelRequest request,java.util.List<ToolDefinition> tools,java.util.List<java.util.Map<String,Object>> history,java.util.function.Consumer<String> chunkConsumer) {
        java.util.Objects.requireNonNull(chunkConsumer, "chunkConsumer");
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        configureConversationThinking(baseUri,model,body);
        var message = body.putArray("messages").addObject();
        message.put("role", "user");
        content(message,request);
        if(!tools.isEmpty()){
            var declarations=body.putArray("tools");for(var tool:tools){try{var fn=declarations.addObject().put("type","function").putObject("function");fn.put("name",tool.name());fn.put("description",tool.description());fn.set("parameters",mapper.readTree(tool.parametersJson()));}catch(Exception invalid){throw new IllegalArgumentException("TOOL_SCHEMA",invalid);}}
            for(var old:history)((com.fasterxml.jackson.databind.node.ArrayNode)body.get("messages")).add(mapper.valueToTree(old));
        }
        RealProviderSmokeAudit audit=null;
        try {
            audit = RealProviderSmokeAudit.begin(baseUri.resolve("chat/completions"), body);
            var builder = java.net.http.HttpRequest.newBuilder(baseUri.resolve("chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            var response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (var lines = response.body()) {
                    throw new ProviderRequestException(response.statusCode(),
                            "openai-compatible streaming HTTP " + response.statusCode() + ": PROVIDER_REJECTED");
                }
            }
            var result = new StringBuilder();var reasoning=new StringBuilder();var streamedTools=new StreamingToolCalls();
            String reportedModel="";
            com.fasterxml.jackson.databind.JsonNode usage = null;
            boolean completed=false;
            try (var lines = response.body()) {
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).strip();
                    if ("[DONE]".equals(data)) {
                        completed=true;
                        break;
                    }
                    var event=mapper.readTree(data);String reported=responseModel(event);
                    if("length".equals(event.path("choices").path(0).path("finish_reason").asText()))throw new ProviderRequestException(0,"STREAM_OUTPUT_LIMIT");
                    if(event.path("usage").isObject()) usage=event.path("usage");
                    if(!reported.isEmpty()){
                        if(!reportedModel.isEmpty()&&!reportedModel.equals(reported))throw new ProviderRequestException(0,"PROVIDER_MODEL_CHANGED_DURING_STREAM");
                        reportedModel=reported;
                    }
                    String reasoningDelta=event.path("choices").path(0).path("delta").path("reasoning_content").asText("");
                    if(reasoning.length()+reasoningDelta.length()>1_000_000)throw new ProviderRequestException(0,"STREAM_REASONING_SIZE_GUARD");
                    reasoning.append(reasoningDelta); // Transport-only continuation; never sent to the player's chat stream.
                    streamedTools.accept(event.path("choices").path(0).path("delta"));
                    String chunk = event.path("choices").path(0)
                            .path("delta").path("content").asText("");
                    if (!chunk.isEmpty()) {
                        if (result.length() + chunk.length() > 1_000_000) {
                            throw new ProviderRequestException(0, "streaming response exceeds limit");
                        }
                        result.append(chunk);
                        chunkConsumer.accept(chunk);
                    }
                }
            }
            if(!completed)throw new ProviderRequestException(0,"STREAM_INCOMPLETE");
            var calls=streamedTools.finish();if(tools.isEmpty()&&!calls.isEmpty())throw new ProviderRequestException(0,"UNEXPECTED_TOOL_CALL");
            if (result.isEmpty()&&calls.isEmpty()) {
                throw new ProviderRequestException(0, "streaming response contained no text");
            }
            if(audit!=null) audit.complete(reportedModel,usage,calls,result.toString());
            return new ToolCompletion(result.toString(),calls,model,reportedModel,reasoning.toString());
        } catch (InterruptedException interrupted) {
            if(audit!=null)audit.failed(interrupted);
            Thread.currentThread().interrupt();
            throw new ProviderRequestException("streaming request interrupted", interrupted);
        } catch (ProviderRequestException failure) {
            if(audit!=null)audit.failed(failure);
            throw failure;
        } catch (Exception failure) {
            if(audit!=null)audit.failed(failure);
            String code=java.util.Objects.toString(failure.getMessage(),"");
            throw new ProviderRequestException(code.matches("(?:TOOL_STREAM|STREAM)_[A-Z_]{1,60}")?code:"streaming request failed", failure);
        }
    }

    private boolean officialDeepSeek(){return "https".equalsIgnoreCase(baseUri.getScheme())&&"api.deepseek.com".equalsIgnoreCase(baseUri.getHost())&&"deepseek-flash".equals(model);}
    static void configureConversationThinking(URI uri,String model,com.fasterxml.jackson.databind.node.ObjectNode body){
        // Explicit user preference; do not send DeepSeek-only parameters to unrelated Providers.
        if("https".equalsIgnoreCase(uri.getScheme())&&"api.deepseek.com".equalsIgnoreCase(uri.getHost())&&"deepseek-flash".equals(model)){
            body.putObject("thinking").put("type","enabled");body.put("reasoning_effort","high");
        }
    }
    private String responseModel(com.fasterxml.jackson.databind.JsonNode response){
        String reported=response.path("model").asText("");
        if(apiKey!=null&&!apiKey.isBlank()&&reported.contains(apiKey))return "";
        return ModelResponse.safeModelName(reported);
    }

    private static void content(com.fasterxml.jackson.databind.node.ObjectNode message,ModelRequest request){
        if(request.images().isEmpty()){message.put("content",request.prompt());return;}
        var parts=message.putArray("content");parts.addObject().put("type","text").put("text",request.prompt());
        for(var image:request.images())parts.addObject().put("type","image_url").putObject("image_url")
                .put("url","data:"+image.mimeType()+";base64,"+java.util.Base64.getEncoder().encodeToString(image.bytes())).put("detail","high");
    }
    private static URI normalize(URI uri) {
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }
}
