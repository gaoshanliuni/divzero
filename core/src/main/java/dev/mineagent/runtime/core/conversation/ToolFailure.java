package dev.mineagent.runtime.core.conversation;

import dev.mineagent.runtime.core.audit.SecretRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.*;

/** Failure is an observation for the same tool call, not a new player instruction. */
public final class ToolFailure {
    public enum Phase { VALIDATION, INTENT_STORAGE, DISPATCH, RECEIPT_STORAGE, READ }
    public static Map<String,Object> result(String tool,UUID operation,Throwable failure,Phase phase){
        var causes=new ArrayList<Map<String,Object>>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        String code="",detail="";int line=-1,column=-1;
        for(Throwable e=failure;e!=null&&causes.size()<8&&seen.add(e);e=e.getCause()){
            if(e instanceof java.util.concurrent.CompletionException||e instanceof java.util.concurrent.ExecutionException)continue;
            String message=safe(e instanceof JsonProcessingException json?json.getOriginalMessage():e.getMessage());
            if(message.matches("[A-Z][A-Z0-9_]{1,100}"))code=message;
            if(!message.isBlank())detail=message;
            causes.add(Map.of("type",e.getClass().getSimpleName(),"message",message));
            if(e instanceof JsonProcessingException json&&json.getLocation()!=null){line=json.getLocation().getLineNr();column=json.getLocation().getColumnNr();}
        }
        boolean read=phase==Phase.READ,notStarted=(phase==Phase.VALIDATION||phase==Phase.INTENT_STORAGE)&&!code.equals("AGENT_TOOL_NOT_REPLAYABLE");
        var out=new LinkedHashMap<String,Object>();out.put("status",read?"READ_FAILED":notStarted?"REJECTED":"UNKNOWN");
        out.put("error",code.isBlank()?(read?"AGENT_READ_FAILED":notStarted?"AGENT_TOOL_REJECTED":"AGENT_TOOL_OUTCOME_UNKNOWN"):code);
        out.put("tool",tool);out.put("operation_id",operation.toString());out.put("phase",phase.name());out.put("executionState",notStarted?"NOT_STARTED":read?"READ_FAILED":"UNKNOWN");
        out.put("diagnostic",detail.isBlank()?"The tool returned no result; its outcome could not be confirmed.":detail);out.put("causes",causes);
        if(notStarted||read)out.put("worldModified",false);out.put("replayAllowed",false);
        if(line>0){out.put("field","arguments");out.put("line",line);out.put("column",column);}
        return ToolErrors.explain(out);
    }
    public static String safe(String text){String clean=SecretRedactor.redact(Objects.toString(text,""));return clean.substring(0,Math.min(clean.length(),4096));}
    private ToolFailure(){}
}
