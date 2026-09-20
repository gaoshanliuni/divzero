package dev.mineagent.runtime.worker.generation;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.worker.provider.ProviderRequestException;
import java.util.*;

/** Only fixed codes cross the Worker/server/UI boundary, never exception messages or HTTP bodies. */
public final class PackageGenerationFailure extends RuntimeException {
    private static final Set<String> CODES=Set.of("PROVIDER_FAILED","PROVIDER_TIMEOUT","PROVIDER_UNSUPPORTED","PROVIDER_NOT_CONFIGURED",
            "OUTPUT_INVALID","PACKAGE_OUTPUT_INVALID","PACKAGE_METADATA_INVALID","RESTORE_REGISTRATION_REQUIRED","CONTENT_STORE_FAILED","CONTENT_STORE_HASH_MISMATCH","STORAGE_NOT_CONFIGURED",
            "RAW_OUTPUT_TOO_LARGE","CONTEXT_LIMIT","NATIVE_CONTEXT_CHANGED","WORKER_TIMEOUT","TRANSPORT_FAILED","RESPONSE_CONTEXT","FAILED","REPAIR_SOURCE_INVALID","CANCELLED_BEFORE_DISPATCH","AUTHORITY_OR_TASK_CHANGED",
            "DEFINITION_INVALID","DEPENDENCY_INVALID","DUPLICATE_FILE","ENCODING_UNSUPPORTED","ENTRYPOINT_FILE_MISMATCH","ENTRYPOINT_INVALID",
            "FILE_LIMIT_EXCEEDED","HASH_MISMATCH","PATH_INVALID","PREFLIGHT_REJECTED","UI_SERVER_RESOURCE","NATIVE_COMPATIBILITY_REQUIRED","NATIVE_COMPATIBILITY_INVALID","NATIVE_COMPATIBILITY_MISMATCH","NATIVE_COMPATIBILITY_TARGET_MISSING","NATIVE_ENVIRONMENT_UNAVAILABLE","NATIVE_ENVIRONMENT_UNVERIFIED");
    public PackageGenerationFailure(String code){super(normalize(code));}
    public static String normalize(Object value){String code=String.valueOf(value);if(code.startsWith("GENERATION_"))code=code.substring(11);return "GENERATION_"+(CODES.contains(code)||dev.mineagent.runtime.core.memory.PlayerPreferenceStore.ERRORS.contains(code)||dev.mineagent.runtime.core.config.ServiceCallBudget.ERRORS.contains(code)||code.matches("PROVIDER_HTTP_[45][0-9]{2}")?code:"FAILED");}
    public static String provider(Throwable error){
        for(int i=0;i<12&&error!=null;i++,error=error.getCause()){
            if(error instanceof dev.mineagent.runtime.core.persistence.ServiceCallLedger.Rejected denied)return denied.getMessage();
            if(error instanceof ProviderRequestException p&&p.statusCode()>=400&&p.statusCode()<=599)return "PROVIDER_HTTP_"+p.statusCode();
            if(timeout(error))return "PROVIDER_TIMEOUT";
        }return "PROVIDER_FAILED";
    }
    public static String transport(Throwable error){
        String preference=dev.mineagent.runtime.core.memory.PlayerPreferenceStore.error(error);if(!preference.isEmpty())return normalize(preference);
        for(int i=0;i<12&&error!=null;i++,error=error.getCause()){
            if(error instanceof PackageGenerationFailure p)return p.getMessage();
            if(timeout(error))return "GENERATION_WORKER_TIMEOUT";
        }return "GENERATION_TRANSPORT_FAILED";
    }
    public static String response(UUID expected,WorkerEnvelope response){
        if(response==null||!expected.equals(response.requestId()))return "GENERATION_RESPONSE_CONTEXT";
        return switch(response.type()){
            case "runtime_package.result","runtime_package.failure"->"";
            case "error"->normalize(response.payload().get("code"));
            default->"GENERATION_RESPONSE_CONTEXT";
        };
    }
    private static boolean timeout(Throwable error){return error instanceof java.util.concurrent.TimeoutException||error instanceof java.net.SocketTimeoutException||error instanceof java.net.http.HttpTimeoutException;}
}
