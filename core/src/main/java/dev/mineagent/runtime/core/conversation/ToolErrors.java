package dev.mineagent.runtime.core.conversation;

import java.util.*;

/** Classifies factual failures without injecting generic continuation instructions. */
public final class ToolErrors {
    private ToolErrors(){}
    public static Map<String,Object> explain(Map<String,Object> original){
        String code=Objects.toString(original.getOrDefault("error",original.getOrDefault("errorCode","")),"");
        if(code.isEmpty())return original;
        var value=new LinkedHashMap<String,Object>(original);String status=Objects.toString(original.get("status"),"");String execution=Objects.toString(original.get("executionState"),"");String category;
        if(status.equals("UNKNOWN")||execution.equals("UNKNOWN")||execution.isEmpty()&&code.matches(".*(?:OUTCOME_UNKNOWN|_UNKNOWN|ACK_TIMEOUT)$")){category="OUTCOME_UNKNOWN";value.putIfAbsent("executionState","UNKNOWN");}
        else if(Set.of("HOST_CONTEXT_CHANGED","HOST_WORLD_SESSION_CHANGED","HOST_CONNECTION_CHANGED","HOST_PLAYER_SESSION_CHANGED","HOST_PLAYER_UNAVAILABLE","HOST_LOCAL_OWNER_CHANGED","HOST_CONVERSATION_CANCELLED","HOST_USER_CANCELLED","HOST_USER_REJECTED_NOT_EXECUTED","HOST_APPROVAL_EXPIRED","HOST_SERVER_STOPPED").contains(code)){category="CANCELLED_CONTEXT";}
        else if(execution.equals("CANDIDATE_ONLY")){category="CANDIDATE_VALIDATION";}
        else if(code.matches(".*(PERMISSION|FORBIDDEN|NOT_OWNED|OWNER_REQUIRED|AUTHORI|DENIED).*")){category="PERMISSION";}
        else if(code.matches(".*(STALE|REVISION|VERSION|HASH_CHANGED|CONFLICT|DIMENSION_CHANGED).*")){category="STALE_TARGET";}
        else if(code.matches(".*(MISSING|UNAVAILABLE|NOT_INSTALLED|DEPENDENCY|UNLOADED).*")){category="DEPENDENCY";}
        else if(code.matches(".*(FULL|EMPTY|SEED|TOOL_BROKEN|RESOURCE|MATERIAL|INSUFFICIENT).*")){category="RESOURCE";}
        else if(code.matches(".*(ARGUMENT|FORMAT|SYNTAX|VALUE|RANGE|POSITION|COORDINATE|FIELD|UNKNOWN_PROPERTY|UNKNOWN_API).*")){category="VALIDATION";}
        else {category="EXECUTION";}
        value.putIfAbsent("category",category);return value;
    }
}
