package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Server-owned live data declarations. These never turn a UI expression into a world write. */
public final class InterfaceSources {
    public record Source(String kind,String objective,String holder,String taskId,String field){}
    public static Map<String,Source> parse(JsonNode root){
        if(root.isMissingNode())return Map.of();if(!root.isObject()||root.size()>64)throw bad("OBJECT");var result=new LinkedHashMap<String,Source>();
        for(var entry:root.properties()){
            if(!entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw bad("KEY");var n=entry.getValue();String kind=n.path("kind").asText();if(!n.isObject())throw bad("OBJECT");
            var fields=switch(kind){case "score"->Set.of("kind","objective","holder");case "agent"->Set.of("kind","field");case "task"->Set.of("kind","task_id","field");default->throw bad("KIND");};
            for(var field:n.properties())if(!fields.contains(field.getKey()))throw bad("FIELD");
            String objective="",holder="",task="",field="";
            if(kind.equals("score")){objective=text(n,"objective",128);holder=text(n,"holder",256);}
            else{field=text(n,"field",24);if(kind.equals("agent")){if(!Set.of("health","max_health","food","name").contains(field))throw bad("AGENT_FIELD");}else{task=UUID.fromString(text(n,"task_id",36)).toString();if(!Set.of("status","title","revision","completed_steps","total_steps").contains(field))throw bad("TASK_FIELD");}}
            result.put(entry.getKey(),new Source(kind,objective,holder,task,field));
        }return Map.copyOf(result);
    }
    private static String text(JsonNode n,String key,int max){var v=n.path(key);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>max||v.asText().codePoints().anyMatch(Character::isISOControl))throw bad("VALUE");return v.asText();}
    private static IllegalArgumentException bad(String code){return new IllegalArgumentException("INTERFACE_SOURCE_"+code);}
    private InterfaceSources(){}
}
