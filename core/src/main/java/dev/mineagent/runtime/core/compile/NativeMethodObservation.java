package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Requires an exact method selector to have appeared in a prior verified members receipt. */
public final class NativeMethodObservation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NativeMethodObservation(){}
    public static void require(List<WorldActionJournal.Batch> history,NativeApiToolRequest body){
        if(body==null||!body.tool().equals("inspect_native_method_body"))throw new IllegalArgumentException("NATIVE_API_METHOD_SOURCE");
        for(var batch:history)for(var receipt:batch.receipts())try{
            if(!receipt.verified()||!receipt.tool().equals("inspect_native_members")||receipt.index()<0||receipt.index()>=batch.actions().size()||!receipt.operationId().equals(UUID.nameUUIDFromBytes((batch.batchId()+"|"+receipt.index()).getBytes(StandardCharsets.UTF_8))))continue;
            var action=batch.actions().get(receipt.index());if(!action.tool().equals(receipt.tool()))continue;var source=NativeApiToolRequest.parse(action.tool(),action.options().get("request"));
            if(!source.snapshot().equals(body.snapshot())||!source.module().equals(body.module())||!source.className().equals(body.className())||!receipt.after().getOrDefault("snapshot","").equals(source.snapshot()))continue;
            var result=JSON.readTree(receipt.after().getOrDefault("result","{}"));for(var method:result.path("methods"))if(method.path("name").isTextual()&&method.path("descriptor").isTextual()&&method.path("name").asText().equals(body.methodName())&&method.path("descriptor").asText().equals(body.descriptor()))return;
        }catch(Exception malformed){/* Ignore malformed or unrelated history; it cannot authorize implementation disclosure. */}
        throw new IllegalStateException("NATIVE_API_METHOD_SOURCE_REQUIRED");
    }
}
