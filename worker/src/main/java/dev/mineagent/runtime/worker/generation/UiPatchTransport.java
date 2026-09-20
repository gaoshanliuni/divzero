package dev.mineagent.runtime.worker.generation;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import java.util.UUID;
public final class UiPatchTransport {
    private UiPatchTransport(){}
    public static String responseError(UUID expected,WorkerEnvelope response){
        if(response==null||!expected.equals(response.requestId()))return "UI_PATCH_RESPONSE_CONTEXT";
        if(response.type().equals("model.result"))return "";
        String code=String.valueOf(response.payload().getOrDefault("message",""));
        if(dev.mineagent.runtime.core.config.ServiceCallBudget.ERRORS.contains(code))return "UI_PATCH_"+code;
        if(code.matches("HTTP_[0-9]{3}"))return "UI_PATCH_"+code;
        return switch(code){case "PROVIDER_TIMEOUT"->"UI_PATCH_TIMEOUT";case "PROVIDER_NOT_CONFIGURED"->"UI_PATCH_PROVIDER_NOT_CONFIGURED";default->"UI_PATCH_PROVIDER_FAILED";};
    }
    public static String failure(Throwable error){
        String preference=dev.mineagent.runtime.core.memory.PlayerPreferenceStore.error(error);if(!preference.isEmpty())return "UI_PATCH_"+preference;
        for(int i=0;i<12&&error!=null;i++,error=error.getCause())if(error instanceof java.util.concurrent.TimeoutException||error instanceof java.net.SocketTimeoutException||error instanceof java.net.http.HttpTimeoutException)return "UI_PATCH_TIMEOUT";
        return "UI_PATCH_TRANSPORT_FAILED";
    }
}
