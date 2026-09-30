package dev.mineagent.runtime.core.conversation;

import java.util.*;

/** Adds corrective guidance without upgrading uncertain execution to a safe retry. */
public final class ToolErrors {
    private ToolErrors(){}
    public static Map<String,Object> explain(Map<String,Object> original){
        String code=Objects.toString(original.getOrDefault("error",original.getOrDefault("errorCode","")),"");
        if(code.isEmpty())return original;
        var value=new LinkedHashMap<String,Object>(original);String status=Objects.toString(original.get("status"),"");String category,advice;
        if(status.equals("UNKNOWN")||code.contains("UNKNOWN")){category="OUTCOME_UNKNOWN";advice="Read the operation receipt and current target state before any write. Never replay an uncertain write.";value.putIfAbsent("executionState","UNKNOWN");}
        else if(code.matches(".*(PERMISSION|FORBIDDEN|NOT_OWNED|OWNER_REQUIRED|AUTHORI|DENIED).*")){category="PERMISSION";advice="Explain the missing permission. Keep the current owner/target; do not retry using a different identity.";}
        else if(code.matches(".*(STALE|REVISION|VERSION|HASH_CHANGED|CONFLICT|DIMENSION_CHANGED).*")){category="STALE_TARGET";advice="Read the original target and latest version, then recompute the requested difference. Do not blindly replace expected revisions.";}
        else if(code.matches(".*(MISSING|UNAVAILABLE|NOT_INSTALLED|DEPENDENCY|UNLOADED).*")){category="DEPENDENCY";advice="Inspect the named dependency or loaded target. Explain unavailable resources; do not invent a successful result.";}
        else if(code.matches(".*(FULL|EMPTY|SEED|TOOL_BROKEN|RESOURCE|MATERIAL|INSUFFICIENT).*")){category="RESOURCE";advice="Inspect actual inventory and authorized supplies. Use the local waiting/resupply state, not repeated identical model calls.";}
        else if(code.matches(".*(ARGUMENT|FORMAT|SYNTAX|VALUE|RANGE|POSITION|COORDINATE|FIELD).*")){category="VALIDATION";advice="Inspect the field-level issues and tool definition. Correct inputs; retry only when executionState confirms NOT_STARTED.";}
        else {category="EXECUTION";advice="Inspect the receipt and affected target before deciding whether a corrected new call is safe.";}
        value.putIfAbsent("category",category);value.putIfAbsent("suggestedAction",advice);return value;
    }
}
