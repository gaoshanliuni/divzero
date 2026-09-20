package dev.mineagent.runtime.worker.smoke;
import java.nio.file.*;
import java.util.*;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.worker.generation.WorkerUiPatchResult;
/** Offline proof using only the outer release JAR plus its actual nested dependency JARs. No model/network/game. */
public final class ProductionUiPatchProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("Expected manifest raw sourceContent work expectMissing");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var base=json.readValue(Files.readString(Path.of(args[0])),RuntimePackage.class);
        String raw=Files.readString(Path.of(args[1]));var source=new ContentAddressedStore(Path.of(args[2]));Path work=Files.createDirectories(Path.of(args[3]));var content=new ContentAddressedStore(work.resolve("content"));
        for(var ref:base.resources().values())content.put(source.read(ref.sha256()));
        var proof=new LinkedHashMap<String,Object>();boolean missing=Boolean.parseBoolean(args[4]);
        try(var signer=IdentitySigner.open(work.resolve("identity"))){
            try{var result=WorkerUiPatchResult.prepare(base,raw,"offline-probe-not-provider",content,signer);proof.put("result",result);if(missing)throw new AssertionError("EXPECTED_MISSING_DEPENDENCY");if(result.candidate()==null)throw new AssertionError(result.errorCode());proof.put("status","PRODUCTION_UI_PARSER_OK");}
            catch(LinkageError error){if(!missing)throw error;proof.put("status","MISSING_PRODUCTION_UI_DEPENDENCY");proof.put("errorClass",error.getClass().getName());proof.put("dependency",String.valueOf(error.getMessage()).startsWith("org/jsoup/")?error.getMessage():"UNEXPECTED");if(!String.valueOf(error.getMessage()).startsWith("org/jsoup/"))throw error;}
        }
        String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));proof.put("rawSha256",hash);proof.put("rawWasStoredBeforeFailure",Files.isRegularFile(content.pathFor(hash)));proof.put("providerCalls",0);Files.writeString(work.resolve("proof.json"),json.writeValueAsString(proof));System.out.println(json.writeValueAsString(proof));
    }
}
