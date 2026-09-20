package dev.mineagent.runtime.worker.generation;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.nio.charset.StandardCharsets;

/** Keep real invalid model output for diagnostics; a failed candidate never becomes a replacement package. */
public record WorkerUiPatchResult(RuntimePackage candidate,String errorCode,String providerId,String rawOutputSha256){
    @FunctionalInterface interface Builder{RuntimePackage build()throws Exception;}
    public static WorkerUiPatchResult prepare(RuntimePackage base,String output,String provider,ContentAddressedStore content,IdentitySigner signer)throws Exception{
        return buildStored(output,provider,content,()->UiPackagePatchBuilder.prepare(base,output,content,signer));
    }
    static WorkerUiPatchResult buildStored(String output,String provider,ContentAddressedStore content,Builder builder)throws Exception{
        if(output==null||output.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IllegalArgumentException("UI_PATCH_OUTPUT_LIMIT");
        String raw=content.put(output.getBytes(StandardCharsets.UTF_8)).sha256();
        try{return new WorkerUiPatchResult(builder.build(),"",provider,raw);}
        catch(LinkageError failure){return new WorkerUiPatchResult(null,"UI_PATCH_DEPENDENCY_MISSING",provider,raw);}
        catch(Exception failure){String code=failure.getMessage();return new WorkerUiPatchResult(null,code!=null&&code.matches("UI_PATCH_[A-Z_]{1,48}")?code:"UI_PATCH_INVALID_OUTPUT",provider,raw);}
    }
}
