package dev.mineagent.runtime.worker.provider;

import java.util.List;

public record ToolCompletion(String text, List<ToolCall> toolCalls, String requestedModel, String responseModel, String reasoningContent) {
    public ToolCompletion(String text,List<ToolCall> toolCalls,String requestedModel,String responseModel){this(text,toolCalls,requestedModel,responseModel,"");}
    public ToolCompletion(String text,List<ToolCall> toolCalls){this(text,toolCalls,"","");}
    public ToolCompletion {
        text = text == null ? "" : text;
        reasoningContent=reasoningContent==null?"":reasoningContent;
        toolCalls = List.copyOf(toolCalls);
        requestedModel=dev.mineagent.runtime.api.model.ModelResponse.safeModelName(requestedModel);
        responseModel=dev.mineagent.runtime.api.model.ModelResponse.safeModelName(responseModel);
    }
}
