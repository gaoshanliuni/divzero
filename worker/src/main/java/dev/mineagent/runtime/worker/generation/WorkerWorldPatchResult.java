package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.nio.charset.StandardCharsets;

public record WorkerWorldPatchResult(RuntimePackage candidate,String errorCode,String providerId,String rawOutputSha256){
    public static WorkerWorldPatchResult prepare(RuntimePackage base,String output,String provider,ContentAddressedStore content,IdentitySigner signer)throws Exception{
        if(output==null||output.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IllegalArgumentException("WORLD_PATCH_OUTPUT_LIMIT");
        String raw=content.put(output.getBytes(StandardCharsets.UTF_8)).sha256();
        try{return new WorkerWorldPatchResult(WorldPackagePatchBuilder.prepare(base,output,content,signer),"",provider,raw);}
        catch(Exception e){String code=e.getMessage();return new WorkerWorldPatchResult(null,code!=null&&code.matches("WORLD_PATCH_[A-Z_]{1,48}")?code:"WORLD_PATCH_INVALID_OUTPUT",provider,raw);}
    }
}
