package dev.mineagent.runtime.core.config;

import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import java.util.*;

/** Optional explicit models for existing phases; blank settings preserve the agent's live selection. */
public final class ExplicitModelRoles {
    public static final List<String> ROLES=List.of("chat","research","code","review","small");
    public static String role(WorkerEnvelope request){
        var payload=request.payload();String explicit=Objects.toString(payload.getOrDefault("modelRole",""),"");if(ROLES.contains(explicit))return explicit;
        if(Objects.toString(payload.get("capability"),"").equals("CODING")||request.type().startsWith("runtime_package.")||request.type().contains("patch.generate"))return "code";
        if(payload.get("toolHistory") instanceof List<?> history)for(int index=history.size()-1;index>=0;index--){
            if(!(history.get(index) instanceof Map<?,?> message)||!(message.get("tool_calls") instanceof List<?> calls))continue;
            for(var call:calls)if(call instanceof Map<?,?> entry&&entry.get("function") instanceof Map<?,?> function){String name=Objects.toString(function.get("name"),"");if(name.startsWith("verify_")||name.startsWith("validate_"))return "review";if(Set.of("web_search","read_web_page","search_images").contains(name))return "research";}
            break;
        }return "chat";
    }
    public static WorkerEnvelope mark(WorkerEnvelope request,String role){if(!ROLES.contains(role))throw new IllegalArgumentException("MODEL_ROLE");var data=new LinkedHashMap<String,Object>(request.payload());data.put("modelRole",role);return new WorkerEnvelope(request.protocolVersion(),request.requestId(),request.type(),data);}
    private ExplicitModelRoles(){}
}
