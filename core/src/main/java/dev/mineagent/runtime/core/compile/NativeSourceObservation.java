package dev.mineagent.runtime.core.compile;

import dev.mineagent.runtime.core.task.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Requires source lookup to be rooted in a prior verified class/member or exact method-body receipt. */
public final class NativeSourceObservation {
    private NativeSourceObservation(){}
    public static void require(List<WorldActionJournal.Batch> history,NativeApiToolRequest source){
        if(source==null||!source.tool().equals("inspect_native_source"))throw new IllegalArgumentException("NATIVE_SOURCE_OBSERVATION");String required=source.methodName().isEmpty()?"inspect_native_members":"inspect_native_method_body";
        for(var batch:history)for(var receipt:batch.receipts())try{if(!receipt.verified()||!receipt.tool().equals(required)||receipt.index()<0||receipt.index()>=batch.actions().size()||!receipt.operationId().equals(UUID.nameUUIDFromBytes((batch.batchId()+"|"+receipt.index()).getBytes(StandardCharsets.UTF_8))))continue;var action=batch.actions().get(receipt.index());if(!action.tool().equals(required))continue;var observed=NativeApiToolRequest.parse(required,action.options().get("request"));if(observed.snapshot().equals(source.snapshot())&&observed.module().equals(source.module())&&observed.className().equals(source.className())&&receipt.after().getOrDefault("snapshot","").equals(source.snapshot())&&(source.methodName().isEmpty()||observed.methodName().equals(source.methodName())&&observed.descriptor().equals(source.descriptor())))return;}catch(Exception malformed){/* Untrusted/malformed history cannot authorize source disclosure. */}
        throw new IllegalStateException("NATIVE_SOURCE_OBSERVATION_REQUIRED");
    }
}
