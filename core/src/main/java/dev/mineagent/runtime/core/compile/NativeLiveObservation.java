package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Requires exact live/transformed method selectors to come from a prior verified members receipt. */
public final class NativeLiveObservation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NativeLiveObservation(){}
    public static void requireMethod(List<WorldActionJournal.Batch> history,NativeLiveToolRequest body){
        String required=switch(body.kind()){case "live_method_body"->"inspect_native_live_members";case "transformed_method_body"->"inspect_native_transformed_members";default->throw new IllegalArgumentException("NATIVE_LIVE_METHOD_SOURCE");};
        for(var batch:history)for(var receipt:batch.receipts())try{if(!receipt.verified()||!receipt.tool().equals(required)||receipt.index()<0||receipt.index()>=batch.actions().size()||!receipt.operationId().equals(UUID.nameUUIDFromBytes((batch.batchId()+"|"+receipt.index()).getBytes(StandardCharsets.UTF_8))))continue;var action=batch.actions().get(receipt.index());if(!action.tool().equals(required))continue;var source=NativeLiveToolRequest.parse(required,action.options().get("request"));boolean same=body.kind().equals("live_method_body")?Objects.equals(source.classToken(),body.classToken()):source.snapshot().equals(body.snapshot())&&source.module().equals(body.module())&&source.className().equals(body.className());if(!same)continue;var result=JSON.readTree(receipt.after().getOrDefault("result","{}"));for(var method:result.path("methods"))if(method.path("name").asText().equals(body.method())&&method.path("descriptor").asText().equals(body.descriptor()))return;}catch(Exception malformed){/* Malformed history cannot authorize implementation lookup. */}
        throw new IllegalStateException("NATIVE_LIVE_METHOD_SOURCE_REQUIRED");
    }
}
