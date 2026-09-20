package dev.mineagent.runtime.core.conversation;

import dev.mineagent.runtime.api.model.ModelResponse;
import java.util.Map;
import java.util.Set;

/** Successful transport attribution. The remote model name is a Provider claim, not independent proof. */
public record ConversationModelReceipt(String providerId, String requestedModel, String responseModel,
                                       long providerConfigurationRevision, String streamMode) {
    public ConversationModelReceipt {
        providerId=ModelResponse.safeModelName(providerId);
        requestedModel=ModelResponse.safeModelName(requestedModel);
        responseModel=ModelResponse.safeModelName(responseModel);
        if(providerConfigurationRevision < -1)throw new IllegalArgumentException("MODEL_RECEIPT_REVISION");
        if(streamMode==null||!Set.of("PROVIDER_STREAM","BUFFERED_PROVIDER_REPLY","NOT_STREAMED").contains(streamMode))streamMode="UNKNOWN";
    }
    public static ConversationModelReceipt from(Map<String,Object> payload){
        long revision=Long.parseLong(String.valueOf(payload.getOrDefault("providerConfigurationRevision",-1)));
        return new ConversationModelReceipt(String.valueOf(payload.getOrDefault("providerId","")),
                String.valueOf(payload.getOrDefault("requestedModel","")),String.valueOf(payload.getOrDefault("responseModel","")),
                revision,String.valueOf(payload.getOrDefault("streamMode","NOT_STREAMED")));
    }
}
